package main

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.stream._

import scala.util.{Failure, Success, Try}
import java.sql.{Connection, DriverManager, ResultSet}

import main.DatabaseActor.QueryResult
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.collection.mutable.ArrayBuffer

/**
  * Created by igor on 25/05/17.
  */
object DatabaseActor {
  val ACTION_STATUS_INITIAL = 0
  val ACTION_STATUS_FINISHED = 99
  val SCHEMA_NAME = "stuff_doer"
  val ACTIONS_TABLE_NAME = "actions"
  val ACTION_COPY_FILE = "copy_file"

  val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"

  val PARAMS_DELIMITER = ","

  case object Shutdown
  case object QueryUnfinishedActions

  case class Action(id: Option[Int], created: DateTime, act_type: String, params: Array[String], status: Int, lastUpdated: DateTime)
  case class QueryDB(query: String, update: Boolean = false)
  case class QueryResult(result: Option[ArrayBuffer[List[String]]], message: String)

  def props(): Props = Props(new DatabaseActor)
}

class DatabaseActor extends Actor with ActorLogging {

  //TODO: Load the unfinished actions from the database. - Update the webserver actor to handle the new return type of
  // getUnfinishedActions.

  var readyToAcceptWork = false

  val materializer = ActorMaterializer()(context)

  override def preStart(): Unit = {
    log.info("Starting...")

    val fullTableName = s"${DatabaseActor.SCHEMA_NAME}.${DatabaseActor.ACTIONS_TABLE_NAME}"
    checkIfTableExists(fullTableName) match {
      case true => log.info(s"Actions table: $fullTableName, exists.")
      case false => log.warning(s"Actions table: $fullTableName, doesn't exists.")
        createTheActionsTable()
    }

    log.info("Started !")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  // TODO: Handle adding a new action.
  override def receive: Receive = {
    case DatabaseActor.Shutdown => controlledTermination()
    case DatabaseActor.QueryUnfinishedActions => sender ! getUnfinishedActions
    case DatabaseActor.QueryDB(query, update) => sender ! queryDataBase(query, update = update)
    case newAction: DatabaseActor.Action => addNewAction(newAction)
    case PoisonPill => controlledTermination()
    case somemessage => log.error(s"Got some unknown message: $somemessage")
  }

  def controlledTermination(): Unit = {
    context.stop(self)
  }

  /**
    * This function converts action string into an Action object.
    * @param parts An action string that was read from an action file.
    * @return The Action object or None.
    */
  def convertToAction(parts: List[String]) : Option[DatabaseActor.Action] = {
    log.info(s"Got parts: $parts")

    validateRawAction(parts) match {
      case true =>
        try {
          val id = parts(0).toInt
          val created = DateTimeFormat.forPattern(DatabaseActor.TIMESTAMP_FORMAT).parseDateTime(parts(1))
          val act_type = parts(2)
          val params = parts(3).split(DatabaseActor.PARAMS_DELIMITER)
          val status = parts(4).toInt
          val lastUpdated = DateTimeFormat.forPattern(DatabaseActor.TIMESTAMP_FORMAT).parseDateTime(parts(5))

          Some(DatabaseActor.Action(Some(id), created, act_type, params, status, lastUpdated))

        } catch {
          case e: Exception =>
            log.error(s"Couldn't convert the action: ${parts.mkString(",")} to case class.")
            None
        }

      case false =>
        log.info("Invalid raw action !")
        None
    }
  }

  /**
    * Checks if there is enough parts in the action.
    * @param parts An array of parts of the action.
    * @return true if enough parts.
    */
  def validateRawAction(parts: List[String]) : Boolean = if (parts.length == 6) true else false

  /**
    * This function executes a query against the database and returns the results as a one long string.
    * @param query The query to execute.
    * @return The result of the query as an array of fields and a relevant message.
    */
  def queryDataBase(query: String, returnHeader: Boolean = false, update: Boolean = false) : QueryResult = {
    Class.forName("org.h2.Driver")
    val conn: Connection = DriverManager.getConnection("jdbc:h2:~/test", "sa", "")

    //"select * from INFORMATION_SCHEMA.TABLES"
    log.info(s"Got the following query: $query, from: $sender")

    val resultTry = update match {
      case false => Try(conn.createStatement().executeQuery(query))
      case true => Try(conn.createStatement().executeUpdate(query))
    }

    val resultToReturn = resultTry match {
      case Success(result: ResultSet) =>
        val rsmd = result.getMetaData
        val colNumber = rsmd.getColumnCount
        val header = for (i <- 1 to colNumber) yield rsmd.getColumnName(i)
        val resultArray = ArrayBuffer.empty[List[String]]
        if (returnHeader) resultArray += header.toList

        while (result.next()) {
          val row = for (i <- 1 to colNumber) yield result.getString(i)
          resultArray += row.toList
        }

        (Some(resultArray), "")
      case Success(result: Int) => (None, s"Updated $result rows !")
      case Success(result) => (None, s"Unexpected result: ${result.toString}")
      case Failure(e) => (None, e.getMessage)
    }

    conn.close()

    QueryResult(resultToReturn._1,resultToReturn._2)
  }

  /**
    * Queries the database and returns a list of unfinished actions.
    * @return An array of unfinished actions.
    */
  def getUnfinishedActions : ArrayBuffer[DatabaseActor.Action] = {
    val result = queryDataBase(s"select * from ${DatabaseActor.SCHEMA_NAME}.${DatabaseActor.ACTIONS_TABLE_NAME} " +
      s"where STATUS=${DatabaseActor.ACTION_STATUS_INITIAL}")
    val actions = result match {
      case QueryResult(Some(listOfRawActions), "") =>
        listOfRawActions.flatMap(convertToAction)
      case (QueryResult(None, msg)) =>
        log.error(s"Got the following message: $msg")
        ArrayBuffer.empty[DatabaseActor.Action]
    }

    actions
  }

  /**
    * Creates the actions table.
    */
  def createTheActionsTable() : Unit = {
    val actionsFullTableName = s"${DatabaseActor.SCHEMA_NAME}.${DatabaseActor.ACTIONS_TABLE_NAME}"
    log.info(s"Creating $actionsFullTableName...")
    val createTableStmt = s"CREATE TABLE $actionsFullTableName (" +
      "ID INT UNIQUE, " +
      "CREATED TIMESTAMP, " +
      "TYPE VARCHAR(255), " +
      "PARAMS LONGVARCHAR, " +
      "STATUS INT," +
      "LASTUPDATED TIMESTAMP" +
      ")"

    val result = queryDataBase(createTableStmt,update = true)

    val message = result match {
      case QueryResult(_, msg) => s"$msg <The table was probably created...>"
      case _ => "Some error occurred while creating the actions table."
    }

    log.info(message)
  }

  /**
    * Check if a table exists and return accordingly.
    * @param name The full name of the table.
    * @return If a table exists or no.
    */
  def checkIfTableExists(name: String) : Boolean = {
    val query = s"SELECT * FROM $name limit 1"

    val result = queryDataBase(query)

    result match {
      case QueryResult(Some(rows), msg) => true
      case _ => false
    }
  }

  // TODO: Add a new action to the database. If the id is None, give it a new Id and insert it into the database.
  def addNewAction(newAction: DatabaseActor.Action) : Unit = {
    // Get a unique id for the action.
    val result = queryDataBase(s"SELECT MAX(ID) FROM ${DatabaseActor.SCHEMA_NAME}.${DatabaseActor.ACTIONS_TABLE_NAME}")
    val id = result match {
      case QueryResult(Some(rows), msg) => rows.head.head.toInt + 1
      case QueryResult(None, msg) =>
        log.error(s"Failed to fetch a new id: $msg. \n For the following action: $newAction")
        -1
    }

    // Insert the action into the database.
    
  }
}
