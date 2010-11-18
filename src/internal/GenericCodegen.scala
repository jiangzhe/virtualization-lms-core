package scala.virtualization.lms
package internal

import java.io.PrintWriter

trait GenericCodegen extends Scheduling {
  val IR: Expressions
  import IR._

  
  def emitBlock(y: Exp[_])(implicit stream: PrintWriter): Unit = {
    val deflist = buildScheduleForResult(y)
    
    for (TP(sym, rhs) <- deflist) {
      emitNode(sym, rhs)
    }
  }

  def getBlockResult[A](s: Exp[A]): Exp[A] = s
  
  def emitNode(sym: Sym[_], rhs: Def[_])(implicit stream: PrintWriter): Unit = {
    throw new Exception("don't know how to generate code for: " + rhs)
  }
  
  def emitValDef(sym: Sym[_], rhs: String)(implicit stream: PrintWriter): Unit

  def quote(x: Exp[_]) = x match {
    case Const(s: String) => "\""+s+"\""
    case Const(z) => z.toString
    case Sym(n) => "x"+n
  }
}



trait GenericNestedCodegen extends GenericCodegen {
  val IR: Expressions with Effects
  import IR._

  var shallow = false

  var outerScope: List[TP[_]] = Nil
  var levelScope: List[TP[_]] = Nil
  var innerScope: List[TP[_]] = null // no, it's not a typo

  override def availableDefs = if (innerScope ne null) innerScope else super.availableDefs

  def focusBlock[A](result: Exp[_])(body: => A): A = {
    
    val saveOuter = outerScope
    val saveLevel = levelScope
    val saveInner = innerScope
    
    outerScope = outerScope ::: levelScope
    
    // ------------ partition current innerScope into new levelScope and innerScope
    
    // if innerScope is set (i.e. we are already focused), it will be used by buildSchedule instead of globalDefs
    
    val e1 = buildScheduleForResult(result) // deep list of deps
    shallow = true
    val e2 = buildScheduleForResult(result) // shallow list of deps (exclude stuff only needed by nested blocks)
    shallow = false

    // shallow is 'must outside + should outside' <--- currently shallow == deep for lambdas, meaning everything 'should outside'
    // bound is 'must inside'

    // find transitive dependencies on bound syms, including their defs (in case of effects)
    val bound = for (TP(sym, rhs) <- e1; s <- boundSyms(rhs)) yield s
    val g1 = getDependentStuff(bound)
    
    levelScope = e1.filter(z => (e2 contains z) && !(g1 contains z)) // shallow (but with the ordering of deep!!) and minus bound

    // sanity check to make sure all effects are accounted for
    result match {
      case Def(Reify(x, effects)) =>
        val actual = levelScope.filter(effects contains _.sym)
        assert(effects == actual.map(_.sym), "violated ordering of effects: expected \n    "+effects+"\nbut got\n    " + actual)
      case _ =>
    }

    innerScope = e1 diff levelScope // delay everything that remains

    // ------------ call body and restore state
    
    val rval = body
    
    outerScope = saveOuter
    levelScope = saveLevel
    innerScope = saveInner
    
    rval
  }


  override def emitBlock(result: Exp[_])(implicit stream: PrintWriter): Unit = {
    focusBlock(result) {
      for (TP(sym, rhs) <- levelScope)
        emitNode(sym, rhs)
    }
  }

/*
  def emitBlock0(start: Exp[_])(implicit stream: PrintWriter): Unit = {
    // try to push stuff as far down into more control-dependent parts as
    // possible. this is the right thing to do for conditionals, but
    // for loops we'll need to do it the other way round (hoist stuff out). 
    // for lambda expressions there is no clear general strategy.

    // TODO: modify buildSchedule to take scope into account (i.e. use globalDefs-scope to look for defs)

    val e1 = buildScheduleForResult(start) // deep list of deps
    shallow = true
    val e2 = buildScheduleForResult(start) // shallow list of deps (exclude stuff only needed by nested blocks)
    shallow = false


    //println("==== deep")
    //e1.foreach(println)
    //println("==== shallow")
    //e2.foreach(println)

    // shallow is 'must outside' <--- this is not true currently since shallow == deep for lambdas
    // bound is 'must inside'

    // find transitive dependencies on bound syms, including their defs (in case of effects)
    val bound = for (TP(sym, rhs) <- e1; s <- boundSyms(rhs)) yield s
    val g1 = getDependentStuff(bound)
    //println("==== bound")
    //println("bound syms: "+bound.mkString(","))
    //g1.foreach(println)
    
    val e3 = e1.filter(z => (e2 contains z) && !(g1 contains z)) // shallow (but with the ordering of deep!!) and minus bound


    // val e3 = e1.filter(e2 contains _) // shallow, but with the ordering of deep!!

    val e4 = e3.filterNot(outerScope contains _) // remove stuff already emitted

    val save = outerScope
    outerScope = e4 ::: outerScope

    // TODO: loop fusion, look for patterns in e4

    // TODO: loop fusion, look for patterns in e4


    // -------------- liveness stuff below (preliminary!!!)

    def usesOf(s: Sym[_]): List[TP[_]] = e1.flatMap { // TODO: 
      case TP(s1, Reify(rhs1, _)) => // reify nodes are eliminated, so we need to find all uses of the reified thing
        if (syms(rhs1).contains(s)) usesOf(s1) else Nil
      case d@TP(_, rhs1) =>
        if (syms(rhs1).contains(s)) List(d) else Nil
    }

    // but we also want to know when stuff gets killed! (or would be killed unless reused)
    // for all syms used by this node, if the use is transient, decrement use count
    
    def usedBy(s: Exp[_]): List[Sym[_]] = s match { // assume for now that all uses are transient
      case Def(Reify(rhs1, _)) => 
        usedBy(rhs1)
      case Def(rhs) =>
        syms(rhs).flatMap {
          case Def(Reify(rhs1, _)) => syms(rhs1)
          case r => List(r)
        }
    }
    
    if (computeLiveness) {
      defuse = e4.flatMap {
        case TP(sym, Reify(_, _)) => Nil
        case TP(sym, rhs) =>
          usesOf(sym).map(d => (sym,d.sym):(Sym[_],Sym[_]))
      }

      //stream.println("// def->use: " + defuse.map(p=>quote(p._1)+"->"+quote(p._2)).mkString(", "))    
    }

    // -------------- emitNode loop
    
    for (TP(sym, rhs) <- e4) {
      emitNode(sym, rhs)
      
      if (computeLiveness) {
        rhs match {
          case Reify(s, effects) =>
          case _ =>

            // remove everything only used here from defuse
            // output dealloc for stuff that goes away
            
            val livebefore = defuse.map(_._1).distinct
            defuse = defuse.filterNot(_._2 == sym)
            val liveafter = defuse.map(_._1).distinct
            val killed = livebefore diff liveafter
            if (killed.nonEmpty) stream.println("// kill: " + killed.map(quote).mkString(", "))
            //stream.println("// def->use: " + defuse.map(p=>quote(p._1)+"->"+quote(p._2)).mkString(", "))

/*
            val u = defuse.filter(_._1 == sym)
            val v = defuse.filter(_._2 == sym)
            stream.println("// used in: " + u.mkString(","))
            stream.println("// decrement usecount of: " + v.mkString(","))
*/            
        }
      }
      
    }

    // -------------- check effects and clean up
    
    start match {
      case Def(Reify(x, effects0)) =>
        // with the current implementation the code below is not
        // really necessary. all effects should have been emitted
        // because of the Reflect dependencies. it's still a good
        // sanity check though

        val effects = effects0.map { case s: Sym[a] => findDefinition(s).get }
        val actual = e4.filter(effects contains _)

        // actual must be a prefix of effects!
        assert(effects.take(actual.length) == actual, 
            "violated ordering of effects: expected \n    "+effects+"\nbut got\n    " + actual)

        val e5 = effects.drop(actual.length)

        for (TP(_, rhs) <- e5) {
          emitNode(Sym(-1), rhs)
        }
      case _ =>
    }

    outerScope = save
  }
*/

  override def getBlockResult[A](s: Exp[A]): Exp[A] = s match {
    case Def(Reify(x, _)) => x
    case _ => super.getBlockResult(s)
  }
  

  override def emitNode(sym: Sym[_], rhs: Def[_])(implicit stream: PrintWriter) = rhs match {
    case Reflect(s, effects) =>
      emitNode(sym, s)
    case Reify(s, effects) =>
      // just ignore -- effects are accounted for in emitBlock
    case _ => super.emitNode(sym, rhs)
  }

}