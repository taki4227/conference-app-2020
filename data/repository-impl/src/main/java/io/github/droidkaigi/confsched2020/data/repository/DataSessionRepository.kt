package io.github.droidkaigi.confsched2020.data.repository

import com.soywiz.klock.DateTime
import io.github.droidkaigi.confsched2020.data.api.DroidKaigiApi
import io.github.droidkaigi.confsched2020.data.api.GoogleFormApi
import io.github.droidkaigi.confsched2020.data.db.SessionDatabase
import io.github.droidkaigi.confsched2020.data.firestore.Firestore
import io.github.droidkaigi.confsched2020.data.repository.mapper.toSession
import io.github.droidkaigi.confsched2020.data.repository.mapper.toSessionFeedback
import io.github.droidkaigi.confsched2020.model.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import timber.log.Timber
import timber.log.debug
import javax.inject.Inject

class DataSessionRepository @Inject constructor(
    private val droidKaigiApi: DroidKaigiApi,
    private val googleFormApi: GoogleFormApi,
    private val sessionDatabase: SessionDatabase,
    private val firestore: Firestore
) : SessionRepository {

    override suspend fun sessionContents(): Flow<SessionContents> = coroutineScope {
        val sessionsFlow = sessions()
            .map {
                it.sortedBy { it.startTime }
            }
        sessionsFlow.map { sessions ->
            val speechSessions = sessions.filterIsInstance<SpeechSession>()
            SessionContents(
                sessions = sessions,
                speakers = speechSessions.flatMap { it.speakers }.distinct(),
                langs = Lang.values().toList(),
                langSupports = LangSupport.values().toList(),
                rooms = sessions.map { it.room }.sortedBy { it.name }.distinct(),
                category = speechSessions.map { it.category }.distinct(),
                audienceCategories = AudienceCategory.values().toList()
            )
        }
    }

    private suspend fun sessions(): Flow<List<Session>> {
        val sessionsFlow = sessionDatabase.sessions()
            .filter { it.isNotEmpty() }
            .onEach { Timber.debug { "sessionDatabase.sessions" } }
        val allSpeakersFlow = sessionDatabase.allSpeaker()
            .onEach { Timber.debug { "sessionDatabase.allSpeaker" } }
        val fabSessionIdsFlow = firestore.getFavoriteSessionIds()
            .onEach { Timber.debug { "firestore.getFavoriteSessionIds" } }

        return combine(
            sessionsFlow,
            allSpeakersFlow,
            fabSessionIdsFlow
        ) { sessionEntities, speakerEntities, fabSessionIds ->
            val firstDay = DateTime(sessionEntities.first().session.stime)
            val sessions: List<Session> = sessionEntities
                .map { it.toSession(speakerEntities, fabSessionIds, firstDay) }
                .sortedWith(compareBy(
                    { it.startTime.unixMillisLong },
                    { it.room.id }
                ))
            sessions
        }
    }

    override suspend fun toggleFavorite(session: Session) {
        firestore.toggleFavorite(session.id)
    }

    override suspend fun sessionFeedback(sessionId: String): SessionFeedback {
        return sessionDatabase.sessionFeedbacks()
            .map { it.toSessionFeedback() }
            .firstOrNull { it.sessionId == sessionId } ?: SessionFeedback(
            sessionId = sessionId,
            totalEvaluation = 0,
            relevancy = 0,
            asExpected = 0,
            difficulty = 0,
            knowledgeable = 0,
            comment = "",
            submitted = false
        )
    }

    override suspend fun saveSessionFeedback(sessionFeedback: SessionFeedback) {
        sessionDatabase.saveSessionFeedback(sessionFeedback)
    }

    override suspend fun submitSessionFeedback(
        session: SpeechSession,
        sessionFeedback: SessionFeedback
    ) {
        val response = googleFormApi.submitSessionFeedback(
            sessionId = session.id,
            sessionTitle = session.title.ja,
            totalEvaluation = sessionFeedback.totalEvaluation,
            relevancy = sessionFeedback.relevancy,
            asExpected = sessionFeedback.asExpected,
            difficulty = sessionFeedback.difficulty,
            knowledgeable = sessionFeedback.knowledgeable,
            comment = sessionFeedback.comment
        )
        // TODO: save local db if success feedback POST
    }

    override suspend fun refresh() {
        val response = droidKaigiApi.getSessions()
        sessionDatabase.save(response)
    }
}
