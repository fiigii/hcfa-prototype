package org.ucombinator.scheme.cfa.kcfa

import org.ucombinator.scheme.cfa.cesk.store.{Store => StoreInterface, LoggingStore, MapStore, SentinelStore}
import org.ucombinator.scheme.syntax._
import org.ucombinator.util.FancyOutput
import org.ucombinator.cfa.AnalysisRunner
import org.ucombinator.scheme.cfa.cesk.CESKMachinery

/**
 * @author Ilya Sergey
 */
trait PointerCESKMachinery extends CESKMachinery with FancyOutput {
  self: AnalysisRunner with KCFAGarbageCollector =>

  type Kont = KStore

  type Addr = (Var, List[Exp])

  /** ******************************************************************
    * Continuation sotre
    * *******************************************************************/
  type KAddr = KAddress

  type KStore = StoreInterface[KAddr, AKont]

  /** ******************************************************************
    * Continuations with pointers
    * *******************************************************************/
  abstract sealed class AKont

  object MT extends AKont

  case class Pointed(frame: Frame, kptr: KAddr) extends AKont

  var misCount = 0
  var returnCount = 0


  /** ******************************************************************
    * Utility functions
    * *******************************************************************/
  def alloc(v: Var, c: Conf): Addr = c match {
    case (PState(e, _, _, kptr), _) =>
      if (isDummy) {
        (SName("SingleAddr", 0), Nil)
      } else k match {
        case 0 => (v, Nil)
        case 1 => (v, List(e))
        case _ => throw new PointerCESKException("Analysis not implemented for k greater than 1 (" + k + ")")
      }
    case _ => {
      throw new PointerCESKException("Illegal allocation configuartion:\n" + c.toString)
    }
  }

  def initState(e: Exp): Conf = {
    val a0: KAddr = InitKAddr
    val newKStore: KStore = new MapStore[KAddr, AKont]() + (a0, Set(MT))
    (PState(e, Map.empty, new MapStore(Map.empty), a0), newKStore)
  }

  val kalloc: (Conf, Exp, Env) => KAddr = kallocPolicy match {
    case "hcfa" =>
      (c: Conf, targetExp: Exp, targetEnv: Env) => {
        HCFAKAddr(targetExp, c._1.asInstanceOf[PState].e, targetEnv)
      }
    case "aac" =>
      (c: Conf, targetExp: Exp, targetEnv: Env) => c match {
        case (PState(exp, env, store: SentinelStore[Addr, Val], _), _) =>
          AACKAddr(exp, env, targetExp, targetEnv, store.store)
      }
    case "kcfa" =>
      (c: Conf, targetExp: Exp, targetEnv: Env) => {
        SimpleKAddr(targetExp)
      }
  }

  abstract class KAddress
  object InitKAddr extends KAddress
  case class HCFAKAddr(exp: Exp, exp1 : Exp, env: Env) extends KAddress
  case class AACKAddr(sexp: Exp, senv: Env, texp: Exp, tenv: Env, store: Store) extends KAddress
  case class SimpleKAddr(exp: Exp) extends KAddress

  def lookupKStore(kstore: KStore, a: KAddr): Set[AKont] = kstore.get(a) match {
    case Some(x) =>
      returnCount += 1
      if(x.size > 1) {
        val s = scala.collection.mutable.Set.empty[Var]
        x.foreach {
          case Pointed(LetFrame(v, _, _), _) => s += v
          case MT =>
        }
        if(s.size > 1) {
          println("mis: " + s)
          misCount += 1
        }
      }
      x
    case None => throw new PointerCESKException("No associated continuations found for an address " +
      a + "\nin store\n" + kstore.toString)
  }

  def updateKStore(kstore: KStore, pair: (KAddr, AKont)) = {
    val (a, k) = pair
    val oldKonts = kstore.getOrElse(a, Set())
    kstore + (a, oldKonts + k)
  }

  class PointerCESKException(s: String) extends CESKException(s)

  /** ******************************************************************
    * Main non-deterministic abstract step function
    * *******************************************************************/
  def mnext: Conf => Set[Conf] = {

    // (let ([x V]) E)
    case c@(PState(LetForm(v, value, body), env, store, kaddr), kstore) if isAtomic(value) => {
      val a = alloc(v, c)
      val env1 = updateEnv(env, v, a)
      val store1 = updateStore(store, a, atomicEval(value, env, store))
      Set((PState(body, env1, store1, kaddr), kstore))
    }

    // (let ([x (if V E E)]) E)
    case c@(PState(e@LetForm(v, i@If(cond, tBranch, eBranch), body), env, store, kaddr), kstore) => {
      val a = alloc(v, c)
      val env1 = updateEnv(env, v, a)
      val frame = LetFrame(v, body, env)
      val condValues = atomicEval(cond, env, store)
      var succs = Set[Conf]()
      if (condValues.exists(_ != BoolLit(false))) {
        val kaddr1 = kalloc(c, tBranch, env)
        val kstore1 = updateKStore(kstore, (kaddr1, Pointed(frame, kaddr)))
        succs = succs + ((PState(tBranch, env1, store, kaddr1), kstore1))
      }
      if (condValues.contains(BoolLit(false))) {
        val kaddr1 = kalloc(c, eBranch, env)
        val kstore1 = updateKStore(kstore, (kaddr1, Pointed(frame, kaddr)))
        succs = succs + ((PState(eBranch, env1, store, kaddr1), kstore1))
      }
      succs
    }

    // (let ([x (set! x V)]) E)
    case c@(PState(LetForm(va, SetVar(v, ae), body), env, store, kaddr), kstore) => {
      val a = alloc(va, c)
      val env1 = updateEnv(env, va, a)
      val addr = lookupEnv(env, v)
      val values = atomicEval(ae, env, store)
      val store1 = updateStore(store, addr, values)
      Set((PState(body, env1, store1, kaddr), kstore))
    }

    // (let ([x (V V ...)]) E)
    case c@(PState(e@LetForm(v, a@AppForm(f, args), body), env, store, kaddr), kstore) => {
      val a = alloc(v, c)
      //val envn = updateEnv(env, v, a)
      val frame = LetFrame(v, body, env)
      atomicEval(f, env, store).flatMap[Conf, Set[Conf]] {
        case Clo(LambdaForm(params, body), env1) => {
          val ai = params.map(alloc(_, c))
          //val env2 = updateEnv(updateEnv(env1, envn.toList), params.zip(ai))
          val env2 = updateEnv(updateEnv(env1, env.toList), params.zip(ai))

          val argVals = args.map(atomicEval(_, env, store))
          val store1 = updateStore(store, ai.zip(argVals))

          val kaddr1 = kalloc(c, body, env)
          val kstore1 = updateKStore(kstore, (kaddr1, Pointed(frame, kaddr)))

          Set((PState(body, env2, store1, kaddr1), kstore1))
        }
      }
    }

    // (if V E E)
    case (PState(If(cond, tBranch, eBranch), env, store, kaddr), kstore) => {
      val condValues = atomicEval(cond, env, store)
      var succs = Set[Conf]()
      if (condValues.exists(_ != BoolLit(false)))
        succs = succs + ((PState(tBranch, env, store, kaddr), kstore))
      if (condValues.contains(BoolLit(false)))
        succs = succs + ((PState(eBranch, env, store, kaddr), kstore))
      succs
    }

    // (set! x V)
    case c@(PState(SetVar(v, ae), env, store, kaddr), kstore) => {
      val addr = lookupEnv(env, v)
      val values = atomicEval(ae, env, store)
      val store1 = updateStore(store, addr, values)
      for {
        k <- lookupKStore(kstore, kaddr)
        next <- returnValue(k, Set(), kstore, c, store1)
      } yield next
    }

    // ae
    case c@(PState(ae, env, store, kaddr), kstore) if isAtomic(ae) => {
      for {
        k <- lookupKStore(kstore, kaddr)
        values = atomicEval(ae, env, store)
        next <- returnValue(k, values, kstore, c, store)
      } yield next
    }

    // (V V ...)
    case c@(PState(e@AppForm(f, args), env, store, kaddr), kstore) => {
      atomicEval(f, env, store).flatMap[Conf, Set[Conf]] {
        case Clo(LambdaForm(params, body), env1) => {
          val ai = params.map(alloc(_, c))
          val env2 = updateEnv(updateEnv(env1, env.toList), params.zip(ai))

          val argVals = args.map(atomicEval(_, env, store))
          val store1 = updateStore(store, ai.zip(argVals))

          val kaddr1 = kalloc(c, body, env)
          val kstore1 = kstore + (kaddr1, kstore(kaddr))

          Set((PState(body, env2, store1, kaddr1), kstore1))
        }
      }
    }

    /** ****************************************************
      * Final state
      * *****************************************************/
    // Ok, folks, that's it!
    case (PFinal(_), _) => Set()

    case c => {
      throw new PointerCESKException("Wrong state: " + c.toString)
    }
  }

  /**
   * Value return
   */
  def returnValue(k: AKont, values: Set[Val], kstore: KStore, c: Conf,  s: Store): Set[Conf] = k match {
    // return to final state
    case MT => Set((PFinal(values), kstore))

    // return from let-statement
    case Pointed(LetFrame(v, e, env), b) => {
      val a = alloc(v, c)
      val rho2 = updateEnv(env, List((v, a)))
      val s1 = updateStore(s, List((a, values)))
      Set((PState(e, rho2, s1, b), kstore))
    }
  }

  def addressOf(exp: Exp, env: Env) : Option[Addr] = exp match {
    case Ref(name) => Some(env(name))
    case _ => None
  }

  def vAddrDeps(c: Configuration) : List[Addr] = {
    val (exp, env, kaddr) = c
    def deps(exp: Exp): List[Addr] = exp match {
      case LetForm(v, value, body) if isAtomic(value) => {
        addressOf(value, env).toList
      }
      case LetForm(v, If(cond, tBranch, eBranch), body) => {
        addressOf(cond, env).toList
      }
      case LetForm(_, SetVar(v, ae), body) => {
        addressOf(ae, env).toList
      }
      case LetForm(v, AppForm(f, args), body) => {
        for (exp <- f :: args; addr <- addressOf(exp, env)) yield addr
      }
      case If(cond, tBranch, eBranch) => {
        addressOf(cond, env).toList
      }
      case SetVar(v, ae) => {
        addressOf(ae, env).toList
      }
      case AppForm(f, args) => {
        for (exp <- f :: args; addr <- deps(exp)) yield addr
      }
      case ae if isAtomic(ae) => {
        addressOf(ae, env).toList
      }
    }
    deps(exp)
  }

  def kAddrDeps(c: Configuration): List[KAddr] = c match {
    case (_, _, k) => List(k)
    //todo: we only should return the address if it is a return state
  }

  type Configuration = (Exp, Env, KAddr)

  def unwiden(c: Conf): Option[(Configuration, Store, KStore)] = c match {
    case (PState(exp, env, store, kaddr), kstore) => Some((exp, env, kaddr), store, kstore)
    case _ => None
  }

  def widen(c: Configuration, s: Store, k: KStore): Conf = {
    val (exp, env, kaddr) = c
    (PState(exp, env, new SentinelStore[Addr, Val](s), kaddr), new SentinelStore[KAddr, AKont](k))
  }

  def widen(c: Conf, s: Store, k: KStore): Conf = c match {
    case (PState(exp, env, _, kptr), _) => (PState(exp, env, s, kptr), k)
    case (state, _) => (state, k)
  }

  /**
   * Kleene iteration of a work set of states
   */
  private def iterateKCFA(initialState: Conf): (Set[(Conf, Conf)], Set[Conf], Int) = {
    var edges = Set[Edge]()
    var count = 0

    var Some((init, globalVStore, globalKStore)) = unwiden(initialState)

    val vdeps = scala.collection.mutable.HashMap[Addr, Set[Configuration]]()
    val kdeps = scala.collection.mutable.HashMap[KAddr, Set[Configuration]]()

    val seen = scala.collection.mutable.Set[Configuration]()
    //val seen = scala.collection.mutable.Set[Conf]()
    var todo = List[Configuration](init)

    seen.add(init)
    for (a <- vAddrDeps(init)) vdeps(a) = vdeps.getOrElse(a, Set()) + init
    for (a <- kAddrDeps(init)) kdeps(a) = kdeps.getOrElse(a, Set()) + init

    while (todo.nonEmpty) {
      val current = todo.head
      todo = todo.tail

      val conf = widen(current, globalVStore, globalKStore)
      /*
      println("Current State :")
      conf match {
        case (PState(c, e, s, kptr), k) =>
        println("State " + count + "---------------------")
        println("C :")
        println(c + "\n")

        println("E :")
        println(e + "\n")

        //println("S :")
        //println(s + "\n")

        println("kptr :")
        println(kptr + "\n")
        println("next :" + k(kptr).size)
        for(n <- k(kptr)) println(n)
        println()
        //println("K : ")
        //println(k)


        case (PFinal(_),_) => println("\n\n Final Todo! Size: " + todo.size + "\n\n")
      }*/

      val nexts = mnext(conf)
      val newEdges = nexts.map(s => (conf, s))

      edges ++= newEdges
      count += 1

      if (isVerbose) {
        println(progressPrefix + " " + count + " states computed so far.")
      }

      for (n <- nexts; (next, nextVStore, nextKStore) <- unwiden(n)) {

        if (!seen.contains(next)) {
          seen.add(next)
          todo = next :: todo
          for (a <- vAddrDeps(next)) vdeps(a) = vdeps.getOrElse(a, Set()) + next
          for (a <- kAddrDeps(next)) kdeps(a) = kdeps.getOrElse(a, Set()) + next
        } /*else {
          println("\nSEEN-----------------\n" + next + "\n-----------------------")
        }*/

        val vdelta = nextVStore match {
          case s: LoggingStore[Addr, Val] => s.changeLog
        }
        if (!vdelta.isEmpty) {
          globalVStore = vdelta(globalVStore)

          //println("Store Change: ")
          //println("vdeps : " + vdelta.dependencies())
          for {
            a <- vdelta.dependencies()
            dep <- vdeps.getOrElse(a, Set())
          } {
            if(!todo.contains(dep)) {
              //println(dep)
              todo = dep :: todo
            }

          }
        }

        val kdelta = nextKStore match {
          case s: LoggingStore[KAddr, AKont] => s.changeLog
        }
        if (!kdelta.isEmpty) {
          globalKStore = kdelta(globalKStore)

          //println("Stack Change: ")
          //println("kdeps :" + kdelta.dependencies())
          for {
            a <- kdelta.dependencies()
            dep <- kdeps.getOrElse(a, Set())
          } {
            if(!todo.contains(dep)) {
              //println(dep)
              todo = dep :: todo
            }
          }
        }

      }

    }

    println("KStore: ")
    for((k,v) <- globalKStore.toList) {
      println(k + "->")
      println("Set:")
      for(s <- v) println(s)
      println("========")
    }

    val finalEdges = edges map {
      case (c1, c2) => (widen(c1, globalVStore, globalKStore), widen(c2, globalVStore, globalKStore))
    }
    val accumStates = finalEdges.map(_._1) ++ finalEdges.map(_._2)

    println("Configurations explored: " + accumStates.size)
    println("States explored: " + count)
    println("return: " + returnCount)
    println("Mismatch Return: " + misCount)
    println("Mismatch: " + misCount / returnCount.toDouble)

    (finalEdges, accumStates, count)
  }

  type Edge = (Conf, Conf)

  def evaluateKCFA(e: Exp): (Set[Edge], Set[Conf], Int) = {
    iterateKCFA(initState(e))
  }

}
