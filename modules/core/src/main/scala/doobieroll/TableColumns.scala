package doobieroll

import cats.implicits._
import cats.data.NonEmptyList
import shapeless._
import shapeless.ops.hlist.{Mapper, ToTraversable}
import shapeless.ops.record.Keys
import shapeless.tag.Tagged
import doobie.Fragment
import doobie.implicits._

import scala.annotation.implicitNotFound

sealed abstract case class TableColumns[T](
  tableName: String,
  allColumns: NonEmptyList[String],
) {

  /** List th fields, separated by commas. e.g. "field1,field2,field3" */
  def list: Fragment = Fragment.const(allColumns.foldSmash("", ",", ""))

  /** List th fields, separated by commas and surrounded by parens.
    * e.g. "(field1,field2,field3)"
    * This makes INSERT queries easier to write like "INSERT INTO mytable VALUES $\{columns.listWithParen}"
    */
  def listWithParen: Fragment = fr"($list)"

  /** Return string of the form '?,?,?' depending on how many fields there is for this TableColumn */
  def parameterized: Fragment = Fragment.const(allColumns.map(_ => "?").foldSmash("", ",", ""))

  def parameterizedWithParen: Fragment =
    fr"($parameterized)"

  /** Prefix each field with the default table name.
    * e.g. "mytable.id, mytable.name, mytable.address"
    */
  def tableNamePrefixed: Fragment =
    Fragment.const(allColumns.map(field => s"$tableName.$field").foldSmash("", ",", ""))

  /** Prefix each field with the given string. e.g. "c.id, c.name, c.address" */
  def prefixed(prefix: String): Fragment =
    Fragment.const(allColumns.map(field => s"$prefix.$field").foldSmash("", ",", ""))

}

object TableColumns {

  private def toSnakeCase(str: String): String =
    str
      .replaceAll(
        "([A-Z]+)([A-Z][a-z])",
        "$1_$2",
      )
      .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
      .toLowerCase

  def deriveSnakeCaseTableColumns[T](tableName: String)(implicit
    mkTableColumns: MkTableColumns[T],
  ): TableColumns[T] =
    deriveTableColumns[T](tableName, toSnakeCase)

  def deriveTableColumns[T](tableName: String, transform: String => String)(implicit
    mkTableColumns: MkTableColumns[T],
  ): TableColumns[T] = {
    val names = mkTableColumns.allColumns.map(transform)
    new TableColumns[T](tableName, names) {}
  }

}

// A separate class to prevent automatic derivation
@implicitNotFound(
  "Cannot derive TableColumns instance. Please check that the type is a case class and has at least 1 parameter",
)
private trait MkTableColumns[T] {
  def allColumns: NonEmptyList[String]
}

private object MkTableColumns {
  object symbolName extends Poly1 {
    implicit def atTaggedSymbol[T]: symbolName.Case[Symbol with Tagged[T]] {
      type Result = String
    } = at[Symbol with Tagged[T]](_.name)
  }

  implicit def familyFormat[
    T,
    Repr <: HList,
    KeysRepr <: HList,
    MapperRepr <: HList,
  ](implicit
    gen: LabelledGeneric.Aux[T, Repr],
    keys: Keys.Aux[Repr, KeysRepr],
    mapper: Mapper.Aux[symbolName.type, KeysRepr, MapperRepr],
    traversable: ToTraversable.Aux[MapperRepr, List, String],
    notHList: Repr =:!= HNil,
  ): MkTableColumns[T] = {
    val _ = (gen, notHList) // These are unused otherwise
    new MkTableColumns[T] {
      override def allColumns: NonEmptyList[String] =
        NonEmptyList.fromListUnsafe(keys().map(symbolName).toList)
    }
  }

}
