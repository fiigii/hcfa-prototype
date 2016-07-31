package org.ucombinator.scheme.cfa.cesk

import org.ucombinator.scheme.syntax._
import org.ucombinator.util.DataUtil._

/**
 * Basic functionality for CESK-based formalisms
 * Direct inheritors:
 *
 * 1. Frame-based CESK for PDCFA
 * 2. Pointer-based CESK for traditional kCFA
 *
 * @author Ilya Sergey
 */

trait CESKMachinery extends StateSpace with PrimOperators {

  /******************************************************
   * Utility functions
   ******************************************************/

  /**
   * An atomic evaluator
   * Denoted as 'A' in the paper.
   *
   * Takes the following expressions:
   *
   * 1. Lambda
   * 2. Ref
   * 3. Unspecified
   */
  def atomicEval(e: Exp, rho: Env, s: Store): Set[Val] = {
    if (!isAtomic(e)) {
      throw new CESKException("Not an atomic expression: " + e.toString)
    } else e match {
      case BadExp => Set.empty
      case PairExp(e1, e2) => for (x <- atomicEval(e1, rho, s);
                                   y <- atomicEval(e2, rho, s)) yield PairLit(x, y)
      case lam@Lambda(_, _) => Set(Clo(lam, rho))
      case Ref(name) => lookupStore(s, lookupEnv(rho, name))
      case Prim(prim, b) => Set(PrimLit(prim, b))
      case Unspecified() => Set()
      case QuoteLit(sexp) => Set(QuotedLit(sexp))
      case NumTopExp => Set(NumTop)
      case SelfLit(SText(str)) => Set(StringLit(str))
      case SelfLit(SBoolean(b)) => Set(BoolLit(b))

      // (REMARK) [No precise numeric literals]
      case SelfLit(SInt(i)) => Set(mkNumLit(i.toLong))
      //      case SelfLit(SInt(i)) => Set(AbstractNumLit)
      case app@App(Prim(primName, _), Arguments(args, _)) => {
        val arg_vals = args.map(ae => atomicEval(ae.exp, rho, s))
        arg_vals.size match {
          case 0 => evalPrimApp(primName, List())
          //          case 1 => for {
          //            a <- arg_vals.head
          //            res <- evalPrimApp(primName, List(a))
          //          } yield res
          //          case 2 => for {
          //            a <- arg_vals.head
          //            b <- arg_vals.tail.head
          //            res <- evalPrimApp(primName, List(a, b))
          //          } yield res
          case n => {
            val argLists: Set[List[Val]] = toSetOfLists(arg_vals)
            val results: Set[Set[Val]] = argLists.map(aList => evalPrimApp(primName, aList))
            results.flatten
          }
        }
      }
      case _ => throw new CESKException("Unexpected atomic expresssion: " + e.toString)
    }
  }

  def isAtomic(ae: Exp): Boolean = ae match {
    case lam@Lambda(_, _) => true
    case Ref(name) => true
    case Prim(name, _) => true
    case QuoteLit(_) => true
    case Unspecified() => true
    case PairExp(_, _) => true
    case SelfLit(SText(str)) => true
    case SelfLit(SBoolean(b)) => true
    case SelfLit(SInt(i)) => true
    case NumTopExp => true
    case BadExp => true
    case _ : AbstractNumLit => true
    case (App(Prim(_, _), Arguments(args, _))) => args.forall(arg => isAtomic(arg.exp))
    case _ => false
  }


  def getBooleanValues(ae: Exp, rho: Env, s: Store): Set[Boolean] = {
    if (!isAtomic(ae)) {
      throw new CESKException("Not an atomic expression: " + ae.toString)
    } else {
      val vals = atomicEval(ae, rho, s)
      vals.map {
        case BoolLit(false) => false
        case _ => true
      }
    }
  }

  def analyseResult(r: Val, rho: Env, s: Store, e: Exp, kptr: KAddr): ControlState = r match {
    case BadVal => ErrorState(e, "Bad value in expression " + e)
    case _ => PState(embedValueToExp(r), rho, s, kptr)
  }

  def embedValueToExp(v: Val): Exp = v match {
    case NumLit(n) => SelfLit(SInt(n))
    case PrimLit(n, b) => Prim(n, b)
    case BoolLit(b) => SelfLit(SBoolean(b))
    case StringLit(s) => SelfLit(SText(s))
    case QuotedLit(e) => QuoteLit(e)
    case PairLit(v1, v2) => {
      PairExp(embedValueToExp(v1), embedValueToExp(v2))
    }
    case NumTop => NumTopExp
    case UnspecifiedVal => Unspecified()
    case BadVal => BadExp
    case _ => throw new CESKException("Value conversion to expression not supported: " + v)
  }


  def getLambdaBodyInANF(lam: Lambda): Exp = {
    val Lambda(_, b) = lam
    val exps = b.exps
    if (exps.size != 1) {
      throw new CESKException("Unexpected number (" + exps.size +
        ") of expressions in ANF at the folowing lambda:\n" +
        lam.toString)
    } else {
      exps.head
    }
  }

  def decomposeLetInANF(let: Let): (Var, Exp, Exp) = let match {
    case Let(bs, body) => {
      val binds = bs.bindings
      if (binds.size != 1) {
        throw new CESKException("Wrong number of bindings in let-expression [ANF]: " + let.toString)
      }
      val Binding(v, e) = binds.head
      val exps = body.exps
      if (exps.size == 0) {
        throw new CESKException("Unexpectedly no expressions in a let-expression [ANF]:\n" + let.toString)
      } else if (exps.size > 1) {
        throw new CESKException("More than one expression in the body of a let-expression [ANF]:\n" + let.toString)
      } else {
        (v, e, exps.head)
      }
    }
  }

  def canHaveEmptyContinuation(c: ControlState) = c match {
    case PFinal(_) => true
    case ErrorState(_, _) => true
    case PState(ae, rho, s, kptr)
      if isAtomic(ae) => true
    case _ => false
  }

  def mustHaveOnlyEmptyContinuation(c: ControlState) = c match {
    case PFinal(_) => true
    case _ => false
  }

  def isValidValueToReturn(v: Val): Boolean = v match {
    case BoolLit(_) | QuotedLit(_) | StringLit(_) => true
    case _: AbstractNumLit => true
    case _ => false
  }

  class CESKException(s: String) extends SemanticException(s)

}

