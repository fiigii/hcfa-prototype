package org.ucombinator.dsg

import org.ucombinator.gc.GCInterface
import org.ucombinator.util.{StringUtils, FancyOutput}

/**
 * Main types and functions for Dyck State Graphs
 *
 * @author Ilya Sergey
 */
trait DSGMachinery {
  self: GCInterface with FancyOutput =>

  val halfHour = 60000 * 30

  /**
   * Abstract components
   */

  //////////////////////////////////////////////////////////////////////////////////////////

  type Frame
  type ControlState
  type Term
  type Value
  type Addr
  type SharedStore = Map[Addr, Set[Value]]

  def initState(e: Term): (ControlState, Kont)

  def step(q: ControlState, k: Kont, frames: Kont, store: SharedStore): Set[(StackAction[Frame], ControlState, SharedStore)]

  def mustHaveOnlyEmptyContinuation(s: ControlState): Boolean

  def canHaveEmptyContinuation(s: ControlState): Boolean

  def canHaveSwitchFrames: Boolean

  def isStoreSensitive(s: ControlState): Boolean

  //////////////////////////////////////////////////////////////////////////////////////////

  /**
   * DSG Nodes
   */
  type S = ControlState
  type Nodes = Set[S]
  type Kont = List[Frame]

  /**
   * DSG Edges
   */
  sealed case class Edge(source: S, g: StackAction[Frame], target: S)

  type Edges = Set[Edge]

  /**
   * DSG = (S, E, s0) -- a Dyck State Graph
   * implicitly parametrized with ? (a set of frames)
   * s0 \in S -- initial node
   */
  sealed case class DSG(nodes: Set[S], edges: Edges, s0: S)

  /**
   * Compute the leas-fixed point by Kleene iteration
   */
  def evaluateDSG(e: Term): (DSG, SharedStore) = {
    val initial = initState(e)
    val initS = initial._1


    val startTime = (new java.util.Date()).getTime

    // Compute the LFP(iterateDSG) recursively
    def eval(next: DSG, helper: NewDSGHelper, shouldProceed: Boolean, statesToVisit: Set[S], store: SharedStore):
    (DSG, NewDSGHelper, SharedStore) = {

      val currentTime = (new java.util.Date()).getTime

      if (!shouldProceed) {
        (next, helper, store)
      } else if (interrupt && next.edges.size > interruptAfter
      || (currentTime - startTime) >= halfHour) {
        (next, helper, store)
      } else {
        val (next2, helper2, goAgain, newToVisit, newStore) = iterateDSG(next, helper, statesToVisit, store)
        eval(next2, helper2, goAgain, newToVisit, newStore)
      }
    }

    val firstDSG = DSG(Set(initS), Set(), initS)
    val firstHelper = new NewDSGHelper
    val (nextDSG, nextHelper, hasNew, toVisit, firstStore) = iterateDSG(firstDSG, firstHelper, Set(initS), Map.empty)

    val (resultDSG, _, newStore) = eval(nextDSG, nextHelper, hasNew, toVisit, firstStore)

    (resultDSG, newStore)
  }

  /**
   * Monotonic DSG iteration function
   * denoted as 'f' in the paper
   */
  private def iterateDSG(dsg: DSG, helper: NewDSGHelper, toVisit: Set[S], store: SharedStore): (DSG, NewDSGHelper, Boolean, Set[S], SharedStore) = dsg match {
    case DSG(ss, ee, s0) => {

      val newNodesEdgesStores: Set[(S, Edge, SharedStore)] = for {
        s <- toVisit
        kont <- helper.getRequiredKont(s, s0)
        possibleFrames = helper.getPossibleStackFrames(s)
        (g, s1, littleStore) <- step(s, kont, possibleFrames, store)
      } yield (s1, Edge(s, g, s1), littleStore)

      val (obtainedStates, obtainedEdges, obtainedStores) = newNodesEdgesStores.unzip3

      // Transform switch edges to pairs of push/pop edges
      val noSwitchesEdges: Edges = if (canHaveSwitchFrames) processSwitchEdges(obtainedEdges) else obtainedEdges
      // Collect new states after decoupling switches
      val newStates: Nodes = (if (canHaveSwitchFrames) {
        val nodes: Set[S] = (noSwitchesEdges -- ee).map {
          case Edge(source, _, target) => target
        }
        nodes ++ obtainedStates
      } else obtainedStates)

      val newEdges = noSwitchesEdges -- ee

      helper.update(newEdges)

      val newStore: SharedStore = obtainedStores.foldLeft(store)(_ ++ _)

      val newToVisit = (newStates
        // Lemma 1 (newEps)
        ++ newStates.flatMap(s => helper.getEpsNextStates(s))
        // Lemma 2 (store-sensitive)
        ++ getStoreSensitiveStates(ss))

//      println("New states: " + newStates.size)
//      println("Eps-dependent: " + (newStates.flatMap(s => helper.getEpsNextStates(s))).size)
//      println("New to visit: " + newToVisit.size)

      val shouldProceed = !newEdges.isEmpty || (store != newStore)

      // S' = ...
      val ss1: Nodes = ss ++ newStates + s0

      // E' = ...
      val ee1 = (ee ++ newEdges)

      println(progressPrefix + " Dyck state graph: " + ss1.size + " nodes and " + ee1.size + " edges.")
      println(newStore)

      // return updated graph
      (DSG(ss1, ee1, s0), helper, shouldProceed, newToVisit, newStore)
    }
  }

  sealed class NewDSGHelper {

    import scala.collection.mutable.{Map => MMap, HashMap => MHashMap}

    private val epsPreds: MMap[S, Nodes] = new MHashMap
    private val epsSuccs: MMap[S, Nodes] = new MHashMap
    private val topFrames: MMap[S, Set[Frame]] = new MHashMap

    /**
     * Let s1 --[+f]--> s2_1 --> .... --> s2_n --[-f]--> s3
     * Then predForPushFrame((s2_i, f)) contains s1
     */
    private val predStateForPushFrame: MMap[(S, Frame), Nodes] = new MHashMap
    private val nonEpsPreds: MMap[S, Nodes] = new MHashMap
    private val possibleStackFrames: MMap[S, Set[Frame]] = new MHashMap

    ////////////////// Public methods //////////////////

    def update(newEdges: Set[Edge]) {
      for (e <- newEdges) {
        e match {
          case Edge(s1, Eps, s2) => equalize(s1, s2)
          case Edge(s1, Pop(f), s2) => processPop(s1, f, s2)
          case Edge(s1, Push(f), s2) => processPush(s1, f, s2)
          case Edge(_, se@Switch(_, _, _), _) => throw new DSGException("Illegal switch edge: " + se)
        }
      }
    }

    /**
     * Constructs a fake continuation with only a top frame (if any)
     */
    def getRequiredKont(s: S, s0: S): Set[Kont] = {
      val frames = gets(topFrames, s)
      if (frames.isEmpty) {
        Set(List())
      } else {
        if (mustHaveOnlyEmptyContinuation(s)) {
          Set(List())

          /**
           * (REMARK)
           * [Valid final candidate]
           * Should carry a value and be epsilon-reachable
           * from the initial state
           */
        } else if (canHaveEmptyContinuation(s)
          && (getEpsPredStates(s)).contains(s0)) {
          frames.map(f => List(f)) + List()
        } else {
          frames.map(f => List(f))
        }
      }
    }

    def getPossibleStackFrames(s: S) = gets(possibleStackFrames, s).toList

    def getEpsNextStates(s: S): Nodes = gets(epsSuccs, s)

    ///////////////// Inner methods ////////////////////

    private def getEpsPredStates(s: S): Nodes = gets(epsPreds, s)


    /**
     * "Equalize" eps-predecessors & eps-successors
     * when an eps-transition s1 --[eps]--> s2 is added
     */
    private def equalize(s1: S, s2: S) {
      val preds = Set(s1) ++ gets(epsPreds, s1)
      val nexts = Set(s2) ++ gets(epsSuccs, s2)

      // Add new successors
      for (s <- preds) {
        puts(epsSuccs, s, nexts)
      }

      // Add new predecessors and top frames
      val topFramesToAdd = preds.flatMap(x => gets(topFrames, x))
      for (s <- nexts) {

        // update eps-predecessors
        puts(epsPreds, s, preds)

        // update top-frames
        puts(topFrames, s, topFramesToAdd)

        // update immediate non-esp predecessors
        for (f <- gets(topFrames, s1)) {
          val predForPushForS1 = gets(predStateForPushFrame, (s1, f))
          puts(predStateForPushFrame, (s, f), predForPushForS1)
        }

        // Update introspective history for GC
        updatePossibleStackFrames(s)
      }
    }

    def updatePossibleStackFrames(s: S) {
      val possibleAsTop = gets(topFrames, s)
      puts(possibleStackFrames, s, possibleAsTop)
      // for all non-eps predecessors of s
      for (spred <- gets(nonEpsPreds, s) ++ gets(epsPreds, s)) {
        // see what are their possible stack-frames
        val newPossibleStackFrames = gets(possibleStackFrames, spred)
        // add them to possible stack frames of s
        puts(possibleStackFrames, s, newPossibleStackFrames)
      }
    }

    /**
     * Update topFrames and predForPushFrames for a new edge s1 --[+f]--> s2
     */
    private def processPush(s1: S, f: Frame, s2: S) {
      val nexts = Set(s2) ++ gets(epsSuccs, s2)
      for (s <- nexts) {
        puts(topFrames, s, Set(f))
        puts(predStateForPushFrame, (s, f), Set(s1))
        puts(nonEpsPreds, s, Set(s1))
        updatePossibleStackFrames(s)
      }
    }

    /**
     * Update eps-graphs for a new egde s1 --[-f]--> s2
     */
    private def processPop(s1: S, f: Frame, s2: S) {
      val newEpsPreds = gets(predStateForPushFrame, (s1, f))
      for (s <- newEpsPreds) {
        equalize(s, s2)
      }
    }

    /**
     * Utility function for multimaps
     */
    private def puts[A, B](map: MMap[A, Set[B]], key: A, newVals: Set[B]) {
      val oldVals = map.getOrElse(key, Set())
      val values = oldVals ++ newVals
      map += ((key, values))
    }

    private def gets[A, B](map: MMap[A, Set[B]], key: A): Set[B] = map.getOrElse(key, Set())

  }

  private def getStoreSensitiveStates(ss: Set[S]) = ss.filter(isStoreSensitive(_))

  /** ************************************************************
    * Some utility methods
    * **************************************************************/

  /**
   * The function exploits the balanced structure of paths in DSG
   * So any "new" stack action cannon affect the status of successor nodes,
   * only "close" predecessors might become epsilon-predecessors.
   */
  def stackActionsEquivalent(g1: Frame, g: Frame): Boolean = {
    g1 == g
  }

  private def processSwitchEdges(edges: Edges): Edges = edges.flatMap {
    case Edge(source, Switch(popped, target: S, pushed), mid) => Set(
      Edge(source, Pop(popped), mid),
      Edge(mid, Push(pushed), target)
    )
    case e => Set(e)
  }

}

class DSGException(s: String) extends Exception(s)
