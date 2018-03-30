package com.prisma.util.gc_value

import com.prisma.api.connector.{NodeSelector, ReallyCoolArgs}
import com.prisma.gc_values._
import com.prisma.shared.models.TypeIdentifier.TypeIdentifier
import com.prisma.shared.models.{Field, Model, TypeIdentifier}
import com.prisma.util.gc_value.OtherGCStuff.sequence
import org.apache.commons.lang.StringEscapeUtils
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat}
import org.joda.time.{DateTime, DateTimeZone}
import org.parboiled2.{Parser, ParserInput}
import org.scalactic.{Bad, Good, Or}
import play.api.libs.json.{JsValue, _}
import sangria.ast.{Field => SangriaField, Value => SangriaValue, _}
import sangria.parser._

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
  * We need a bunch of different converters from / to GC values
  *
  * 1.  DBValue       <->  GCValue     for writing into typed value fields in the Client-DB
  * 2.  SangriaValue  <->  GCValue     for transforming the Any we get from Sangria per field back and forth
  * 3.  DBString      <->  GCValue     for writing defaultValues in the System-DB since they are always a String, and JSArray for Lists
  * 4.  Json          <->  GCValue     for SchemaSerialization
  * 5.  SangriaValue  <->  String      for reading and writing default values
  * 6.  InputString   <->  GCValue     chains String -> SangriaValue -> GCValue and back
  */
/**
  * 0. This gets us a GCValue as String or Any without requiring context like field it therefore only works from GCValue
  * Can be made a singleton
  */
object GCValueExtractor {

  def fromGCValueToString(t: GCValue): String = {
    fromGCValue(t) match {
      case x: Vector[Any] => x.map(_.toString).mkString(start = "[", sep = ",", end = "]")
      case x              => x.toString
    }
  }

  def fromGCValue(t: GCValue): Any = {
    t match {
      case x: ListGCValue => x.values.map(fromGCValue)
      case x: RootGCValue => sys.error("RootGCValues not implemented yet in GCValueExtractor")
      case x: LeafGCValue => fromLeafGCValue(x)
    }
  }

  def fromLeafGCValue(t: LeafGCValue): Any = {
    t match {
      case NullGCValue        => None // todo danger!!!
      case x: StringGCValue   => x.value
      case x: EnumGCValue     => x.value
      case x: IdGCValue       => x.value
      case x: DateTimeGCValue => x.value
      case x: IntGCValue      => x.value
      case x: FloatGCValue    => x.value
      case x: BooleanGCValue  => x.value
      case x: JsonGCValue     => x.value
    }
  }

  def fromGCValueToOption(t: GCValue): Option[Any] = {
    import spray.json._
    t match {
      case NullGCValue        => None // todo danger!!!
      case x: StringGCValue   => Some(x.value)
      case x: EnumGCValue     => Some(x.value)
      case x: IdGCValue       => Some(x.value)
      case x: DateTimeGCValue => Some(x.value)
      case x: IntGCValue      => Some(x.value)
      case x: FloatGCValue    => Some(x.value)
      case x: BooleanGCValue  => Some(x.value)
      case x: JsonGCValue     => Some(x.value.toString.parseJson)
      case x: ListGCValue     => Some(x.values.map(fromGCValue))
      case x: RootGCValue     => sys.error("RootGCValues not implemented yet in GCValueExtractor")
    }
  }

  def fromGCValueToJson(t: GCValue): JsValue = {

    val formatter = ISODateTimeFormat.dateHourMinuteSecondFraction()

    t match {
      case NullGCValue         => JsNull
      case StringGCValue(x)    => JsString(x)
      case EnumGCValue(x)      => JsString(x)
      case IdGCValue(x)        => JsString(x)
      case DateTimeGCValue(x)  => JsString(formatter.print(x))
      case IntGCValue(x)       => JsNumber(x)
      case FloatGCValue(x)     => JsNumber(x)
      case BooleanGCValue(x)   => JsBoolean(x)
      case JsonGCValue(x)      => x
      case ListGCValue(values) => JsArray(values.map(fromGCValueToJson))
      case RootGCValue(map)    => JsObject(map.map { case (k, v) => (k, fromGCValueToJson(v)) })
    }
  }

  def fromListGCValue(t: ListGCValue): Vector[Any] = t.values.map(fromGCValue)
}

/**
  * 1. DBValue <-> GCValue - This is used write and read GCValues to typed Db fields in the ClientDB
  */
case class GCDBValueConverter(typeIdentifier: TypeIdentifier, isList: Boolean) extends GCConverter[Any] {
  import play.api.libs.json.{JsObject => PlayJsObject}
  import spray.json.{JsObject => SprayJsObject}

  override def toGCValue(t: Any): Or[GCValue, InvalidValueForScalarType] = {
    try {
      val result = (t, typeIdentifier) match {
        case (x: String, TypeIdentifier.String)   => StringGCValue(x)
        case (x: Int, TypeIdentifier.Int)         => IntGCValue(x)
        case (x: Float, TypeIdentifier.Float)     => FloatGCValue(x)
        case (x: Double, TypeIdentifier.Float)    => FloatGCValue(x)
        case (x: Boolean, TypeIdentifier.Boolean) => BooleanGCValue(x)
        case (x: java.sql.Timestamp, TypeIdentifier.DateTime) =>
          DateTimeGCValue(DateTime.parse(x.toString, DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS").withZoneUTC()))
        case (x: DateTime, TypeIdentifier.DateTime)  => DateTimeGCValue(x)
        case (x: String, TypeIdentifier.GraphQLID)   => IdGCValue(x)
        case (x: String, TypeIdentifier.Enum)        => EnumGCValue(x)
        case (x: String, TypeIdentifier.Json)        => JsonGCValue(Json.parse(x))
        case (x: PlayJsObject, TypeIdentifier.Json)  => JsonGCValue(x)
        case (x: SprayJsObject, TypeIdentifier.Json) => JsonGCValue(Json.parse(x.compactPrint))
        case (x: Vector[Any], _) if isList           => sequence(x.map(this.toGCValue)).map(seq => ListGCValue(seq)).get
        case (None, _)                               => NullGCValue
        case _                                       => sys.error("Error in GCDBValueConverter. Value: " + t.toString)
      }

      Good(result)
    } catch {
      case NonFatal(_) => Bad(InvalidValueForScalarType(t.toString, typeIdentifier.toString))
    }
  }

  def fromGCValueToString(t: GCValue): String = {
    fromGCValue(t) match {
      case x: Vector[Any] => x.map(_.toString).mkString(start = "[", sep = ",", end = "]")
      case x              => x.toString
    }
  }

  override def fromGCValue(t: GCValue): Any = {
    t match {
      case NullGCValue        => None
      case x: StringGCValue   => x.value
      case x: EnumGCValue     => x.value
      case x: IdGCValue       => x.value
      case x: DateTimeGCValue => x.value //todo needs fitting format for Sql
      case x: IntGCValue      => x.value
      case x: FloatGCValue    => x.value
      case x: BooleanGCValue  => x.value
      case x: JsonGCValue     => x.value
      case x: ListGCValue     => x.values.map(this.fromGCValue)
      case x: RootGCValue     => sys.error("RootGCValues not implemented yet in GCDBValueConverter")
    }
  }
}

/**
  * 2. SangriaAST <-> GCValue - This is used to transform Sangria parsed values into GCValue and back
  */
case class GCSangriaValueConverter(typeIdentifier: TypeIdentifier, isList: Boolean) extends GCConverter[SangriaValue] {
  import OtherGCStuff._

  override def toGCValue(t: SangriaValue): Or[GCValue, InvalidValueForScalarType] = {
    try {
      val result = (t, typeIdentifier) match {
        case (_: NullValue, _)                                                                   => NullGCValue
        case (x: StringValue, _) if x.value == "null" && typeIdentifier != TypeIdentifier.String => NullGCValue
        case (x: StringValue, TypeIdentifier.String)                                             => StringGCValue(x.value)
        case (x: BigIntValue, TypeIdentifier.Int)                                                => IntGCValue(x.value.toInt)
        case (x: BigIntValue, TypeIdentifier.Float)                                              => FloatGCValue(x.value.toDouble)
        case (x: BigDecimalValue, TypeIdentifier.Float)                                          => FloatGCValue(x.value.toDouble)
        case (x: FloatValue, TypeIdentifier.Float)                                               => FloatGCValue(x.value)
        case (x: BooleanValue, TypeIdentifier.Boolean)                                           => BooleanGCValue(x.value)
        case (x: StringValue, TypeIdentifier.DateTime)                                           => DateTimeGCValue(new DateTime(x.value, DateTimeZone.UTC))
        case (x: StringValue, TypeIdentifier.GraphQLID)                                          => IdGCValue(x.value)
        case (x: EnumValue, TypeIdentifier.Enum)                                                 => EnumGCValue(x.value)
        case (x: StringValue, TypeIdentifier.Json)                                               => JsonGCValue(Json.parse(x.value))
        case (x: ListValue, _) if isList                                                         => sequence(x.values.map(this.toGCValue)).map(seq => ListGCValue(seq)).get
        case _                                                                                   => sys.error("Error in GCSangriaASTConverter. Value: " + t.renderCompact)
      }

      Good(result)
    } catch {
      case NonFatal(_) => Bad(InvalidValueForScalarType(t.renderCompact, typeIdentifier.toString))
    }
  }

  override def fromGCValue(gcValue: GCValue): SangriaValue = {

    val formatter = ISODateTimeFormat.dateHourMinuteSecondFraction()

    gcValue match {
      case NullGCValue        => NullValue()
      case x: StringGCValue   => StringValue(value = x.value)
      case x: IntGCValue      => BigIntValue(x.value)
      case x: FloatGCValue    => FloatValue(x.value)
      case x: BooleanGCValue  => BooleanValue(x.value)
      case x: IdGCValue       => StringValue(x.value)
      case x: DateTimeGCValue => StringValue(formatter.print(x.value))
      case x: EnumGCValue     => EnumValue(x.value)
      case x: JsonGCValue     => StringValue(Json.prettyPrint(x.value))
      case x: ListGCValue     => ListValue(values = x.values.map(this.fromGCValue))
      case x: RootGCValue     => sys.error("Default Value cannot be a RootGCValue. Value " + x.toString)
    }
  }
}

/**
  * 3. DBString <-> GCValue - This is used to write the defaultValue as a String to the SystemDB and read it from there
  *
  * NOT USED ANYMORE SINCE WE STORE THE SCHEMA AS JSON
  */
/**
  * 4. Json <-> GC Value - This is used to encode and decode the Schema in the SchemaSerializer.
  */
case class GCJsonConverter(typeIdentifier: TypeIdentifier, isList: Boolean) extends GCConverter[JsValue] {
  import OtherGCStuff._

  override def toGCValue(t: JsValue): Or[GCValue, InvalidValueForScalarType] = {

    (t, typeIdentifier) match {
      case (JsNull, _)                             => Good(NullGCValue)
      case (x: JsString, TypeIdentifier.String)    => Good(StringGCValue(x.value))
      case (x: JsNumber, TypeIdentifier.Int)       => Good(IntGCValue(x.value.toInt))
      case (x: JsNumber, TypeIdentifier.Float)     => Good(FloatGCValue(x.value.toDouble))
      case (x: JsBoolean, TypeIdentifier.Boolean)  => Good(BooleanGCValue(x.value))
      case (x: JsString, TypeIdentifier.DateTime)  => Good(DateTimeGCValue(new DateTime(x.value, DateTimeZone.UTC)))
      case (x: JsString, TypeIdentifier.GraphQLID) => Good(IdGCValue(x.value))
      case (x: JsString, TypeIdentifier.Enum)      => Good(EnumGCValue(x.value))
      case (x: JsArray, _) if isList               => sequence(x.value.toVector.map(this.toGCValue)).map(seq => ListGCValue(seq))
      case (x: JsValue, TypeIdentifier.Json)       => Good(JsonGCValue(x))
      case (x, _)                                  => Bad(InvalidValueForScalarType(x.toString, typeIdentifier.toString))
    }
  }

  override def fromGCValue(gcValue: GCValue): JsValue = {
    val formatter = ISODateTimeFormat.dateHourMinuteSecondFraction()

    gcValue match {
      case NullGCValue        => JsNull
      case x: StringGCValue   => JsString(x.value)
      case x: EnumGCValue     => JsString(x.value)
      case x: IdGCValue       => JsString(x.value)
      case x: DateTimeGCValue => JsString(formatter.print(x.value))
      case x: IntGCValue      => JsNumber(x.value)
      case x: FloatGCValue    => JsNumber(x.value)
      case x: BooleanGCValue  => JsBoolean(x.value)
      case x: JsonGCValue     => x.value
      case x: ListGCValue     => JsArray(x.values.map(this.fromGCValue))
      case x: RootGCValue     => JsObject(x.map.mapValues(this.fromGCValue))
    }
  }
}

/**
  * 5. String <-> SangriaAST - This reads and writes Default Values we get/need as String.
  */
class MyQueryParser(val input: ParserInput) extends Parser with Tokens with Ignored with Operations with Fragments with Values with Directives with Types

case class StringSangriaValueConverter(typeIdentifier: TypeIdentifier, isList: Boolean) {
  import OtherGCStuff._

  def from(string: String): Or[SangriaValue, InvalidValueForScalarType] = {

    val escapedIfNecessary = typeIdentifier match {
      case _ if string == "null"               => string
      case TypeIdentifier.DateTime if !isList  => escape(string)
      case TypeIdentifier.String if !isList    => escape(string)
      case TypeIdentifier.GraphQLID if !isList => escape(string)
      case TypeIdentifier.Json                 => escape(string)
      case _                                   => string
    }

    val parser = new MyQueryParser(ParserInput(escapedIfNecessary))

    parser.Value.run() match {
      case Failure(e) => e.printStackTrace(); Bad(InvalidValueForScalarType(string, typeIdentifier.toString))
      case Success(x) => Good(x)
    }
  }

  def fromAbleToHandleJsonLists(string: String): Or[SangriaValue, InvalidValueForScalarType] = {

    if (isList && typeIdentifier == TypeIdentifier.Json) {
      try {
        Json.parse(string) match {
          case JsNull     => Good(NullValue())
          case x: JsArray => sequence(x.value.toVector.map(x => from(x.toString))).map(seq => ListValue(seq))
          case _          => Bad(InvalidValueForScalarType(string, typeIdentifier.toString))
        }
      } catch {
        case e: Exception => Bad(InvalidValueForScalarType(string, typeIdentifier.toString))
      }
    } else {
      from(string)
    }
  }

  def to(sangriaValue: SangriaValue): String = {
    sangriaValue match {
      case _: NullValue                                          => sangriaValue.renderCompact
      case x: StringValue if !isList                             => unescape(sangriaValue.renderCompact)
      case x: ListValue if typeIdentifier == TypeIdentifier.Json => x.values.map(y => unescape(y.renderCompact)).mkString(start = "[", sep = ",", end = "]")
      case _                                                     => sangriaValue.renderCompact
    }
  }

  private def escape(str: String): String   = "\"" + StringEscapeUtils.escapeJava(str) + "\""
  private def unescape(str: String): String = StringEscapeUtils.unescapeJava(str).stripPrefix("\"").stripSuffix("\"")
}

/**
  * 6. String <-> GC Value - This combines the StringSangriaConverter and GCSangriaValueConverter for convenience.
  */
case class GCStringConverter(typeIdentifier: TypeIdentifier, isList: Boolean) extends GCConverter[String] {

  override def toGCValue(t: String): Or[GCValue, InvalidValueForScalarType] = {

    for {
      sangriaValue <- StringSangriaValueConverter(typeIdentifier, isList).fromAbleToHandleJsonLists(t)
      result       <- GCSangriaValueConverter(typeIdentifier, isList).toGCValue(sangriaValue)
    } yield result
  }

  override def fromGCValue(t: GCValue): String = {
    val sangriaValue = GCSangriaValueConverter(typeIdentifier, isList).fromGCValue(t)
    StringSangriaValueConverter(typeIdentifier, isList).to(sangriaValue)
  }

  def fromGCValueToOptionalString(t: GCValue): Option[String] = {
    t match {
      case NullGCValue => None
      case value       => Some(fromGCValue(value))
    }
  }
}

/**
  * 7. Any <-> GCValue - This is used to transform Sangria arguments
  */
case class GCAnyConverter(typeIdentifier: TypeIdentifier, isList: Boolean) extends GCConverter[Any] {
  import OtherGCStuff._
  import play.api.libs.json.{JsArray => PlayJsArray, JsObject => PlayJsObject}
  import spray.json.{JsArray => SprayJsArray, JsObject => SprayJsObject}

  override def toGCValue(t: Any): Or[GCValue, InvalidValueForScalarType] = {
    try {
      val result = (t, typeIdentifier) match {
        case (_: NullValue, _)                                                        => NullGCValue
        case (x: String, _) if x == "null" && typeIdentifier != TypeIdentifier.String => NullGCValue
        case (x: String, TypeIdentifier.String)                                       => StringGCValue(x)
        case (x: Int, TypeIdentifier.Int)                                             => IntGCValue(x.toInt)
        case (x: BigInt, TypeIdentifier.Int)                                          => IntGCValue(x.toInt)
        case (x: BigInt, TypeIdentifier.Float)                                        => FloatGCValue(x.toDouble)
        case (x: BigDecimal, TypeIdentifier.Float)                                    => FloatGCValue(x.toDouble)
        case (x: Float, TypeIdentifier.Float)                                         => FloatGCValue(x)
        case (x: Double, TypeIdentifier.Float)                                        => FloatGCValue(x)
        case (x: Boolean, TypeIdentifier.Boolean)                                     => BooleanGCValue(x)
        case (x: String, TypeIdentifier.DateTime)                                     => DateTimeGCValue(new DateTime(x))
        case (x: DateTime, TypeIdentifier.DateTime)                                   => DateTimeGCValue(x)
        case (x: String, TypeIdentifier.GraphQLID)                                    => IdGCValue(x)
        case (x: String, TypeIdentifier.Enum)                                         => EnumGCValue(x)
        case (x: PlayJsObject, TypeIdentifier.Json)                                   => JsonGCValue(x)
        case (x: SprayJsObject, TypeIdentifier.Json)                                  => JsonGCValue(Json.parse(x.compactPrint))
        case (x: String, TypeIdentifier.Json)                                         => JsonGCValue(Json.parse(x))
        case (x: SprayJsArray, TypeIdentifier.Json)                                   => JsonGCValue(Json.parse(x.compactPrint))
        case (x: PlayJsArray, TypeIdentifier.Json)                                    => JsonGCValue(x)
        case (x: List[Any], _) if isList                                              => sequence(x.map(this.toGCValue).toVector).map(seq => ListGCValue(seq)).get
        case _                                                                        => sys.error("Error in toGCValue. Value: " + t)
      }

      Good(result)
    } catch {
      case NonFatal(_) => Bad(InvalidValueForScalarType(t.toString, typeIdentifier.toString))
    }
  }

  override def fromGCValue(t: GCValue): Any = GCValueExtractor.fromGCValue(t)
}

/**
  * 7. CoolArgs <-> ReallyCoolArgs - This is used to transform from Coolargs for create on a model to typed ReallyCoolArgs
  */
case class GCCreateReallyCoolArgsConverter(model: Model) {

  def toReallyCoolArgs(raw: Map[String, Any]): ReallyCoolArgs = {

    val res = model.scalarNonListFields.map { field =>
      val converter = GCAnyConverter(field.typeIdentifier, false)

      val gCValue = raw.get(field.name) match {
        case Some(Some(x)) => converter.toGCValue(x).get
        case Some(None)    => NullGCValue
        case Some(x)       => converter.toGCValue(x).get
        case None          => NullGCValue
      }
      field.name -> gCValue
    }
    ReallyCoolArgs(RootGCValue(res: _*))
  }

  def toReallyCoolArgsFromJson(json: JsValue): ReallyCoolArgs = {

    def fromSingleJsValue(jsValue: JsValue, field: Field): GCValue = jsValue match {
      case JsString(x)                                                    => StringGCValue(x)
      case JsNumber(x) if field.typeIdentifier == TypeIdentifier.Int      => IntGCValue(x.toInt)
      case JsNumber(x) if field.typeIdentifier == TypeIdentifier.Float    => FloatGCValue(x.toDouble)
      case JsBoolean(x) if field.typeIdentifier == TypeIdentifier.Boolean => BooleanGCValue(x)
      case _                                                              => sys.error("Unhandled JsValue")
    }

    val res = model.scalarNonListFields.map { field =>
      val gCValue: JsLookupResult = json \ field.name
      val asOption                = gCValue.toOption
      val converted = asOption match {
        case None                                                              => NullGCValue
        case Some(JsNull)                                                      => NullGCValue
        case Some(JsString(x))                                                 => StringGCValue(x)
        case Some(JsNumber(x)) if field.typeIdentifier == TypeIdentifier.Int   => IntGCValue(x.toInt)
        case Some(JsNumber(x)) if field.typeIdentifier == TypeIdentifier.Float => FloatGCValue(x.toDouble)
        case Some(JsBoolean(x))                                                => BooleanGCValue(x)
        case Some(JsArray(x)) if field.isList                                  => ListGCValue(x.map(v => fromSingleJsValue(v, field)).toVector)
        case Some(x: JsValue) if field.typeIdentifier == TypeIdentifier.Json   => JsonGCValue(x)
        case x                                                                 => sys.error("Not implemented yet: " + x)

      }
      field.name -> converted
    }
    ReallyCoolArgs(RootGCValue(res: _*))
  }
}

/**
  * This validates a GCValue against the field it is being used on, for example after an UpdateFieldMutation
  */
object OtherGCStuff {
  def isValidGCValueForField(value: GCValue, field: Field): Boolean = {
    (value, field.typeIdentifier) match {
      case (NullGCValue, _)                              => true
      case (_: StringGCValue, TypeIdentifier.String)     => true
      case (_: IdGCValue, TypeIdentifier.GraphQLID)      => true
      case (_: EnumGCValue, TypeIdentifier.Enum)         => true
      case (_: JsonGCValue, TypeIdentifier.Json)         => true
      case (_: DateTimeGCValue, TypeIdentifier.DateTime) => true
      case (_: IntGCValue, TypeIdentifier.Int)           => true
      case (_: FloatGCValue, TypeIdentifier.Float)       => true
      case (_: BooleanGCValue, TypeIdentifier.Boolean)   => true
      case (x: ListGCValue, _) if field.isList           => x.values.map(isValidGCValueForField(_, field)).forall(identity)
      case (_: RootGCValue, _)                           => false
      case (_, _)                                        => false
    }
  }

  /**
    * This helps convert Or listvalues.
    */
  def sequence[A, B](seq: Vector[Or[A, B]]): Or[Vector[A], B] = {
    def recurse(seq: Vector[Or[A, B]])(acc: Vector[A]): Or[Vector[A], B] = {
      if (seq.isEmpty) {
        Good(acc)
      } else {
        seq.head match {
          case Good(x)    => recurse(seq.tail)(acc :+ x)
          case Bad(error) => Bad(error)
        }
      }
    }
    recurse(seq)(Vector.empty)
  }

  /**
    * This is used to parse SQL exceptions for references of specific GCValues
    */
  def parameterString(where: NodeSelector) = where.fieldValue match {
    case StringGCValue(x)      => s"parameters ['$x',"
    case IntGCValue(x)         => s"parameters [$x,"
    case FloatGCValue(x)       => s"parameters [$x,"
    case BooleanGCValue(false) => s"parameters [0,"
    case BooleanGCValue(true)  => s"parameters [1,"
    case IdGCValue(x)          => s"parameters ['$x',"
    case EnumGCValue(x)        => s"parameters ['$x',"
    case DateTimeGCValue(x)    => s"parameters ['${dateTimeFromISO8601(x)}'," // Todo
    case JsonGCValue(x)        => s"parameters ['$x'," // Todo
    case ListGCValue(_)        => sys.error("Not an acceptable Where")
    case RootGCValue(_)        => sys.error("Not an acceptable Where")
    case NullGCValue           => sys.error("Not an acceptable Where")
  }

  private def dateTimeFromISO8601(v: Any) = {
    val string = v.toString
    //"2017-12-05T12:34:23.000Z" to "2017-12-05T12:34:23.000" which MySQL will accept
    string.replace("Z", "")
  }

}
