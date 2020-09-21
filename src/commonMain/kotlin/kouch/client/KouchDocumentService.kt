package kouch.client

import io.ktor.client.statement.*
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Put
import io.ktor.http.HttpStatusCode.Companion.Accepted
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Conflict
import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.http.HttpStatusCode.Companion.Forbidden
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.NotModified
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kouch.*


class KouchDocumentService(
    val context: Context,
    type: Type,
) {
    enum class Type {
        DOC,
        DESIGN
    }

    val pathPart = when (type) {
        Type.DOC -> ""
        Type.DESIGN -> "_design/"
    }

    suspend inline fun <reified T : KouchEntity> get(
        id: String,
        db: DatabaseName = context.getMetadata(T::class).databaseName,
        getQueryParameters: KouchDocument.GetQueryParameters? = null
    ): T? {
        val queryString = context.systemQueryParametersJson.encodeNullableToUrl(getQueryParameters)

        val response = context.request(
            method = Get,
            path = "${db.value}/$pathPart$id$queryString"
        )

        val text = response.readText()
        return when (response.status) {
            //TODO : error if <T> removed
            OK -> context.decodeKouchEntityFromJsonElement<T>(
                context.responseJson.parseToJsonElement(text).filterNonUnderscoredFieldsWithIdRev()
            )
            NotFound -> null
            NotModified,
            BadRequest,
            Unauthorized,
            Forbidden
            -> throw KouchDocumentException("$response: $text")
            else -> throw UnsupportedStatusCodeException("$response: $text")
        }
    }

    suspend inline fun <reified T : KouchEntity> getWithResponse(
        id: String,
        db: DatabaseName = context.getMetadata(T::class).databaseName,
        getQueryParameters: KouchDocument.GetQueryParameters? = null
    ): Pair<KouchDocument.GetResponse, T?> {
        val queryString = context.systemQueryParametersJson.encodeNullableToUrl(getQueryParameters)

        val response = context.request(
            method = Get,
            path = "${db.value}/$pathPart$id$queryString"
        )

        val text = response.readText()
        return when (response.status) {
            OK -> {
                context
                    .responseJson
                    .parseToJsonElement(text)
                    .splitUnderscoredAndNonUnderscoredFields()
                    .let { (responseJson, entityJson) ->
                        Pair(
                            context.systemJson.decodeFromJsonElement(responseJson),
                            context.decodeKouchEntityFromJsonElement<T>(entityJson),
                        )
                    }
            }
            NotFound -> Pair(
                context.systemJson.decodeFromJsonElement(context.systemJson.parseToJsonElement(text).filterUnderscoredFields()),
                null
            )
            NotModified,
            BadRequest,
            Unauthorized,
            Forbidden
            -> throw KouchDocumentException("$response: $text")
            else -> throw UnsupportedStatusCodeException("$response: $text")
        }
    }

    suspend inline fun <reified T : KouchEntity> insert(
        entity: T,
        metadata: KouchMetadata.Entity = context.getMetadata(T::class),
        putQueryParameters: KouchDocument.PutQueryParameters? = null
    ): PutResult<T> = when {
        entity.id.isBlank() -> throw IdIsBlankException(entity.toString())
        entity.revision != null -> throw RevisionIsNotNullException(entity.toString())
        else -> upsert(
            entity = entity,
            metadata = metadata,
            putQueryParameters = putQueryParameters
        )
    }

    suspend inline fun <reified T : KouchEntity> update(
        entity: T,
        metadata: KouchMetadata.Entity = context.getMetadata(T::class),
        putQueryParameters: KouchDocument.PutQueryParameters? = null
    ): PutResult<T> = when {
        entity.id.isBlank() -> throw IdIsBlankException(entity.toString())
        entity.revision == null -> throw RevisionIsNullException(entity.toString())
        else -> upsert(
            entity = entity,
            metadata = metadata,
            putQueryParameters = putQueryParameters
        )
    }


    class PutResult<T>(
        private val getResponseCallback: () -> KouchDocument.PutResponse,
        private val getUpdatedEntityCallback: () -> T
    ) {
        fun getResponseAndUpdatedEntity() = getResponseCallback() to getUpdatedEntityCallback()
        fun getResponse() = getResponseCallback()
        fun getUpdatedEntity() = getUpdatedEntityCallback()
    }

    suspend inline fun <reified T : KouchEntity> upsert(
        entity: T,
        metadata: KouchMetadata = context.getMetadata(T::class),
        putQueryParameters: KouchDocument.PutQueryParameters? = null
    ): PutResult<T> {
        val queryString = context.systemQueryParametersJson.encodeNullableToUrl(putQueryParameters)

        val response = when (metadata) {
            is KouchMetadata.Entity -> context.request(
                method = Put,
                path = "${metadata.databaseName.value}/$pathPart${entity.id}$queryString",
                body = context.encodeToKouchEntity(entity, metadata.className)
            )
            is KouchMetadata.Design -> context.request(
                method = Put,
                path = "${metadata.databaseName.value}/$pathPart${entity.id}$queryString",
                body = context.encodeToKouchDesign(entity)
            )
        }


        val text = response.readText()
        return when (response.status) {
            Created,
            Accepted
            -> {
                val getResponseCallback = { context.responseJson.decodeFromString<KouchDocument.PutResponse>(text) }
                val getUpdatedEntityCallback = { entity.copyWithRevision(getResponseCallback().rev ?: throw ResponseRevisionIsNullException(text)) }
                PutResult(getResponseCallback, getUpdatedEntityCallback)

//                val responseBody = context.responseJson.decodeFromString<KouchDocument.PutResponse>(text)
//                val updatedEntity = entity.copyWithRevision(responseBody.rev ?: throw ResponseRevisionIsNullException(text))
//                updatedEntity to responseBody
            }
            BadRequest,
            Unauthorized,
            NotFound,
            Conflict
            -> throw KouchDocumentException("$response: $text")
            else -> throw UnsupportedStatusCodeException("$response: $text")
        }
    }


    suspend inline fun <reified T : KouchEntity> delete(
        entity: T,
        batch: Boolean = false,
        db: DatabaseName = context.getMetadata(T::class).databaseName
    ): () -> KouchDocument.DeleteResponse {
        val id = entity.id
        val revision = entity.revision
        return when {
            id.isBlank() -> throw IdIsBlankException(entity.toString())
            revision == null -> throw RevisionIsNullException(entity.toString())
            else -> delete(
                id = id,
                revision = revision,
                batch = batch,
                db = db
            )
        }
    }

    suspend inline fun <reified T : KouchEntity> delete(id: String, revision: String, batch: Boolean = false) =
        delete(
            id = id,
            revision = revision,
            batch = batch,
            db = context.getMetadata(T::class).databaseName
        )

    suspend inline fun delete(id: String, revision: String, batch: Boolean = false, db: DatabaseName): () -> KouchDocument.DeleteResponse {
        val batchStr = if (batch) "ok" else null
        return delete(
            id = id,
            db = db,
            deleteQueryParameters = KouchDocument.DeleteQueryParameters(rev = revision, batch = batchStr)
        )
    }


    suspend inline fun delete(
        id: String,
        db: DatabaseName,
        deleteQueryParameters: KouchDocument.DeleteQueryParameters
    ): () -> KouchDocument.DeleteResponse {
        val queryString = context.systemQueryParametersJson.encodeToUrl(deleteQueryParameters)
        println(queryString)
        val response = context.request(
            method = Delete,
            path = "${db.value}/$id$queryString",
        )

        val text = response.readText()
        return when (response.status) {
            OK,
            Accepted,
            NotFound
            -> {
                { context.systemJson.decodeFromString(text) }
            }
            BadRequest,
            Unauthorized,

            Conflict
            -> throw KouchDocumentException("$response: $text")
            else -> throw UnsupportedStatusCodeException("$response: $text")
        }
    }
}