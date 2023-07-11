package no.nav.delta.event

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.toList
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    title: String,
    description: String,
    startTime: Timestamp,
    endTime: Timestamp,
    location: String?,
): Event {
    val out: Event
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
"""
INSERT INTO event(owner, title, description, start_time, end_time, location)
    VALUES (?, ?, ?, ?, ?, ?)
RETURNING *;
""",
            )

        preparedStatement.setString(1, ownerEmail)
        preparedStatement.setString(2, title)
        preparedStatement.setString(3, description)
        preparedStatement.setTimestamp(4, startTime)
        preparedStatement.setTimestamp(5, endTime)
        preparedStatement.setString(6, location)

        val result = preparedStatement.executeQuery()
        result.next()
        out = result.resultSetToEvent()
        connection.commit()
    }

    return out
}

fun DatabaseInterface.getEvent(id: String): Either<EventNotFoundException, Event> {
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?)")
        preparedStatement.setString(1, id)
        val result = preparedStatement.executeQuery()

        if (!result.next()) return EventNotFoundException.left()
        return result.resultSetToEvent().right()
    }
}

fun DatabaseInterface.getFutureEvents(): List<Event> {
    val events: MutableList<Event>
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE end_time > now();")
        val result = preparedStatement.executeQuery()
        events = result.toList { resultSetToEvent() }
    }
    return events
}

fun DatabaseInterface.getEventsByOwner(ownerEmail: String): List<Event> {
    val events: MutableList<Event>
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE owner=?")
        preparedStatement.setString(1, ownerEmail)
        val result = preparedStatement.executeQuery()
        events = result.toList { resultSetToEvent() }
    }
    return events
}

fun DatabaseInterface.registerForEvent(eventId: String, email: String): Either<RegisterForEventError, UUID> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId).flatMap {
            checkIfParticipantIsRegistered(connection, eventId, email)
        }.flatMap {
            checkIfEventIsFull(connection, eventId)
        }.flatMap {
            val preparedStatement =
                connection.prepareStatement(
                    "INSERT INTO participant(event_id, email) VALUES (uuid(?), ?) RETURNING otp;",
                )
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val result: ResultSet = preparedStatement.executeQuery()
            result.next()
            connection.commit()
            UUID.fromString(result.getString("otp")).right()
        }
    }
}

fun DatabaseInterface.unregisterFromEvent(eventId: String, otp: String): Either<UnregisterFromEventError, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId).flatMap {
            val preparedStatement =
                connection.prepareStatement(
                    "DELETE FROM participant WHERE event_id=uuid(?) AND otp=uuid(?);",
                )
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, otp)

            val rowsAffected = preparedStatement.executeUpdate()
            if (rowsAffected == 0) return InvalidOtpException.left()
            Unit.right()
        }
    }
}

fun ResultSet.resultSetToEvent(): Event {
    return Event(
        id = UUID.fromString(getString("id")),
        ownerEmail = getString("owner"),
        title = getString("title"),
        description = getString("description"),
        startTime = getTimestamp("start_time"),
        endTime = getTimestamp("end_time"),
        location = getString("location"),
    )
}

fun checkIfEventExists(connection: Connection, eventId: String): Either<EventNotFoundException, Unit> {
    connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?);").use { preparedStatement ->
        preparedStatement.setString(1, eventId)
        val result = preparedStatement.executeQuery()
        if (!result.next()) return EventNotFoundException.left()
    }
    return Either.Right(Unit)
}

fun checkIfParticipantIsRegistered(connection: Connection, eventId: String, email: String): Either<ParticipantAlreadyRegisteredException, Unit> {
    connection.prepareStatement("SELECT * FROM participant WHERE event_id=uuid(?) AND email=?;")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)
            val result = preparedStatement.executeQuery()
            if (result.next()) {
                ParticipantAlreadyRegisteredException.left()
            }
            return Unit.right()
        }
}

fun checkIfEventIsFull(connection: Connection, eventId: String): Either<EventIsFullException, Unit> {
    connection.prepareStatement("SELECT * FROM participant WHERE event_id=uuid(?);")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)
            val result = preparedStatement.executeQuery()
            if (result.next()) {
                EventIsFullException.left()
            }
            return Unit.right()
        }
}
