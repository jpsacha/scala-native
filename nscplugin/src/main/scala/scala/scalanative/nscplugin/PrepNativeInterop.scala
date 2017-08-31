package scala.scalanative
package nscplugin

import scala.tools.nsc
import nsc._

import scala.collection.immutable.ListMap
import scala.collection.mutable.Buffer

/**
 * This phase does:
 * - Rewrite calls to scala.Enumeration.Value (include name string) (Ported from ScalaJS)
 * - Rewrite the body `scala.util.PropertiesTrait.scalaProps` to
 *   avoid calls to `getResourceByStream` if the resource to read
 *   is `/library.properties`.
 */
abstract class PrepNativeInterop
    extends plugins.PluginComponent
    with transform.Transform {
  import PrepNativeInterop._

  val nirAddons: NirGlobalAddons {
    val global: PrepNativeInterop.this.global.type
  }

  import global._
  import nirAddons.nirDefinitions._
  import definitions._
  import rootMirror._

  val phaseName: String            = "nativeinterop"
  override def description: String = "prepare ASTs for Native interop"

  override def newPhase(p: nsc.Phase): StdPhase = new NativeInteropPhase(p)

  class NativeInteropPhase(prev: nsc.Phase) extends Phase(prev) {
    override def name: String        = phaseName
    override def description: String = PrepNativeInterop.this.description
  }

  override protected def newTransformer(unit: CompilationUnit): Transformer =
    new NativeInteropTransformer(unit)

  private object nativenme {
    val hasNext      = newTermName("hasNext")
    val next         = newTermName("next")
    val nextName     = newTermName("nextName")
    val x            = newTermName("x")
    val Value        = newTermName("Value")
    val Val          = newTermName("Val")
    val scalaProps   = newTermName("scalaProps")
    val propFilename = newTermName("propFilename")
  }

  class NativeInteropTransformer(unit: CompilationUnit) extends Transformer {

    /** Kind of the directly enclosing (most nested) owner. */
    private var enclosingOwner: OwnerKind = OwnerKind.None

    /** Cumulative kinds of all enclosing owners. */
    private var allEnclosingOwners: OwnerKind = OwnerKind.None

    /** Nicer syntax for `allEnclosingOwners is kind`. */
    private def anyEnclosingOwner: OwnerKind = allEnclosingOwners

    /** Nicer syntax for `allEnclosingOwners isnt kind`. */
    private object noEnclosingOwner {
      @inline def is(kind: OwnerKind): Boolean =
        allEnclosingOwners isnt kind
    }

    private def enterOwner[A](kind: OwnerKind)(body: => A): A = {
      require(kind.isBaseKind, kind)
      val oldEnclosingOwner     = enclosingOwner
      val oldAllEnclosingOwners = allEnclosingOwners
      enclosingOwner = kind
      allEnclosingOwners |= kind
      try {
        body
      } finally {
        enclosingOwner = oldEnclosingOwner
        allEnclosingOwners = oldAllEnclosingOwners
      }
    }

    override def transform(tree: Tree): Tree =
      tree match {
        // Catch the definition of scala.Enumeration itself
        case cldef: ClassDef if cldef.symbol == EnumerationClass =>
          enterOwner(OwnerKind.EnumImpl) { super.transform(cldef) }

        // Catch Scala Enumerations to transform calls to scala.Enumeration.Value
        case idef: ImplDef if isScalaEnum(idef) =>
          val kind =
            if (idef.isInstanceOf[ModuleDef]) OwnerKind.EnumMod
            else OwnerKind.EnumClass
          enterOwner(kind) { super.transform(idef) }

        // Catch (Scala) ClassDefs
        case cldef: ClassDef =>
          enterOwner(OwnerKind.NonEnumScalaClass) { super.transform(cldef) }

        // Catch (Scala) ModuleDefs
        case modDef: ModuleDef =>
          enterOwner(OwnerKind.NonEnumScalaMod) { super.transform(modDef) }

        // ValOrDefDef's that are local to a block must not be transformed
        case vddef: ValOrDefDef if vddef.symbol.isLocalToBlock =>
          super.transform(tree)

        // `DefDef` that initializes `lazy val scalaProps` in trait `PropertiesTrait`
        // We rewrite the body to return a pre-propulated `Properties` if the resource
        // to read is `/library.properties`.
        case dd @ DefDef(mods, name, Nil, Nil, tpt, rhs)
            if dd.symbol == PropertiesTrait.info.member(nativenme.scalaProps) =>
          val nrhs = shortCircuitLibraryProperties(dd, unit.freshTermName _)
          treeCopy.DefDef(tree, mods, name, Nil, Nil, transform(tpt), nrhs)

        // Catch ValDefs in enumerations with simple calls to Value
        case ValDef(mods, name, tpt, ScalaEnumValue.NoName(optPar))
            if anyEnclosingOwner is OwnerKind.Enum =>
          val nrhs = scalaEnumValName(tree.symbol.owner, tree.symbol, optPar)
          treeCopy.ValDef(tree, mods, name, transform(tpt), nrhs)

        // Catch Select on Enumeration.Value we couldn't transform but need to
        // we ignore the implementation of scala.Enumeration itself
        case ScalaEnumValue.NoName(_)
            if noEnclosingOwner is OwnerKind.EnumImpl =>
          reporter.warning(
            tree.pos,
            """Couldn't transform call to Enumeration.Value.
              |The resulting program is unlikely to function properly as this
              |operation requires reflection.""".stripMargin
          )
          super.transform(tree)

        case ScalaEnumValue.NullName()
            if noEnclosingOwner is OwnerKind.EnumImpl =>
          reporter.warning(
            tree.pos,
            """Passing null as name to Enumeration.Value
              |requires reflection at runtime. The resulting
              |program is unlikely to function properly.""".stripMargin
          )
          super.transform(tree)

        case ScalaEnumVal.NoName(_) if noEnclosingOwner is OwnerKind.EnumImpl =>
          reporter.warning(
            tree.pos,
            """Calls to the non-string constructors of Enumeration.Val
              |require reflection at runtime. The resulting
              |program is unlikely to function properly.""".stripMargin
          )
          super.transform(tree)

        case ScalaEnumVal.NullName()
            if noEnclosingOwner is OwnerKind.EnumImpl =>
          reporter.warning(
            tree.pos,
            """Passing null as name to a constructor of Enumeration.Val
              |requires reflection at runtime. The resulting
              |program is unlikely to function properly.""".stripMargin
          )
          super.transform(tree)

        case _ => super.transform(tree)
      }
  }

  private def isScalaEnum(implDef: ImplDef) =
    implDef.symbol.tpe.typeSymbol isSubClass EnumerationClass

  private trait ScalaEnumFctExtractors {
    protected val methSym: Symbol

    protected def resolve(ptpes: Symbol*) = {
      val res = methSym suchThat {
        _.tpe.params.map(_.tpe.typeSymbol) == ptpes.toList
      }
      assert(res != NoSymbol)
      res
    }

    protected val noArg    = resolve()
    protected val nameArg  = resolve(StringClass)
    protected val intArg   = resolve(IntClass)
    protected val fullMeth = resolve(IntClass, StringClass)

    /**
     * Extractor object for calls to the targeted symbol that do not have an
     * explicit name in the parameters
     *
     * Extracts:
     * - `sel: Select` where sel.symbol is targeted symbol (no arg)
     * - Apply(meth, List(param)) where meth.symbol is targeted symbol (i: Int)
     */
    object NoName {
      def unapply(t: Tree): Option[Option[Tree]] = t match {
        case sel: Select if sel.symbol == noArg =>
          Some(None)
        case Apply(meth, List(param)) if meth.symbol == intArg =>
          Some(Some(param))
        case _ =>
          None
      }
    }

    object NullName {
      def unapply(tree: Tree): Boolean = tree match {
        case Apply(meth, List(Literal(Constant(null)))) =>
          meth.symbol == nameArg
        case Apply(meth, List(_, Literal(Constant(null)))) =>
          meth.symbol == fullMeth
        case _ => false
      }
    }

  }

  private object ScalaEnumValue extends {
    protected val methSym = getMemberMethod(EnumerationClass, nativenme.Value)
  } with ScalaEnumFctExtractors

  private object ScalaEnumVal extends {
    protected val methSym = {
      val valSym = getMemberClass(EnumerationClass, nativenme.Val)
      valSym.tpe.member(nme.CONSTRUCTOR)
    }
  } with ScalaEnumFctExtractors

  /**
   * Construct a call to Enumeration.Value
   * @param thisSym  ClassSymbol of enclosing class
   * @param nameOrig Symbol of ValDef where this call will be placed
   *                 (determines the string passed to Value)
   * @param intParam Optional tree with Int passed to Value
   * @return Typed tree with appropriate call to Value
   */
  private def scalaEnumValName(thisSym: Symbol,
                               nameOrig: Symbol,
                               intParam: Option[Tree]) = {

    val defaultName = nameOrig.asTerm.getterName.encoded

    // Construct the following tree
    //
    //   if (nextName != null && nextName.hasNext)
    //     nextName.next()
    //   else
    //     <defaultName>
    //
    val nextNameTree = Select(This(thisSym), nativenme.nextName)
    val nullCompTree =
      Apply(Select(nextNameTree, nme.NE), Literal(Constant(null)) :: Nil)
    val hasNextTree = Select(nextNameTree, nativenme.hasNext)
    val condTree    = Apply(Select(nullCompTree, nme.ZAND), hasNextTree :: Nil)
    val nameTree = If(condTree,
                      Apply(Select(nextNameTree, nativenme.next), Nil),
                      Literal(Constant(defaultName)))
    val params = intParam.toList :+ nameTree

    typer.typed {
      Apply(Select(This(thisSym), nativenme.Value), params)
    }
  }

  /**
   * Rewrite the rhs of `lazy val scalaProps` in trait `PropertiesTrait` to return a pre-populated
   * `java.util.Properties` if the resource to read is `/library.properties`.
   * @param original  The original `DefDef`
   * @param freshName A function that generates a fresh name
   * @return The new (typed) rhs of the given `DefDef`.
   */
  private def shortCircuitLibraryProperties(
      original: DefDef,
      freshName: String => TermName): Tree = {
    val libraryFileName = "/library.properties"

    // Construct the following tree
    //
    //   if (PropertiesTrait.this.propFilename.equals("/library.properties") {
    //     val fresh = new java.util.Properties()
    //     // populate fresh
    //     fresh
    //   } else {
    //     <original rhs>
    //   }
    //
    val thisSym          = original.symbol.owner
    val propFileNametree = Select(This(thisSym), nativenme.propFilename)
    val equalsTree       = Select(propFileNametree, "equals")
    val libStringTree    = Literal(Constant(libraryFileName))
    val condTree         = Apply(equalsTree, libStringTree :: Nil)
    val thnTree = {
      val stream = classOf[Option[_]].getResourceAsStream(libraryFileName)
      val props  = new java.util.Properties()
      try props.load(stream)
      finally stream.close()

      val instanceName = freshName("properties")
      val keys         = props.stringPropertyNames().iterator()
      val puts         = Buffer.empty[Tree]
      while (keys.hasNext()) {
        val key   = keys.next()
        val value = props.getProperty(key)
        puts += Apply(Select(Ident(instanceName), newTermName("put")),
                      List(Literal(Constant(key)), Literal(Constant(value))))
      }
      val bindTree =
        ValDef(Modifiers(), instanceName, TypeTree(), New(JavaProperties))
      Block(bindTree :: puts.toList, Ident(instanceName))
    }
    val ifTree = If(condTree, thnTree, original.rhs)

    typer.atOwner(original.symbol).typed(ifTree)
  }

}

object PrepNativeInterop {
  private final class OwnerKind(val baseKinds: Int) extends AnyVal {
    import OwnerKind._

    @inline def isBaseKind: Boolean =
      Integer.lowestOneBit(baseKinds) == baseKinds && baseKinds != 0 // exactly 1 bit on

    @inline def |(that: OwnerKind): OwnerKind =
      new OwnerKind(this.baseKinds | that.baseKinds)

    @inline def is(that: OwnerKind): Boolean =
      (this.baseKinds & that.baseKinds) != 0

    @inline def isnt(that: OwnerKind): Boolean =
      !this.is(that)
  }

  private object OwnerKind {

    /** No owner, i.e., we are at the top-level. */
    val None = new OwnerKind(0x00)

    // Base kinds - those form a partition of all possible enclosing owners

    /** A Scala class/trait that does not extend Enumeration. */
    val NonEnumScalaClass = new OwnerKind(0x01)

    /** A Scala object that does not extend Enumeration. */
    val NonEnumScalaMod = new OwnerKind(0x02)

    /** A Scala class/trait that extends Enumeration. */
    val EnumClass = new OwnerKind(0x40)

    /** A Scala object that extends Enumeration. */
    val EnumMod = new OwnerKind(0x80)

    /** The Enumeration class itself. */
    val EnumImpl = new OwnerKind(0x100)

    // Compound kinds

    /** A Scala class/trait, possibly Enumeration-related. */
    val ScalaClass = NonEnumScalaClass | EnumClass | EnumImpl

    /** A Scala object, possibly Enumeration-related. */
    val ScalaMod = NonEnumScalaMod | EnumMod

    /** A Scala class, trait or object */
    val ScalaThing = ScalaClass | ScalaMod

    /** A Scala class/trait/object extending Enumeration, but not Enumeration itself. */
    val Enum = EnumClass | EnumMod

  }
}
