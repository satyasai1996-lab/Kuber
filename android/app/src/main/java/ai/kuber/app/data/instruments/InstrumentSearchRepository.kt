package ai.kuber.app.data.instruments

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InstrumentSearchRepository(
    private val client: InstrumentSearchClient,
) {
    private val mutableState = MutableStateFlow<InstrumentSearchState>(InstrumentSearchState.Idle)
    private val requestGeneration = AtomicLong(0)
    val state: StateFlow<InstrumentSearchState> = mutableState.asStateFlow()

    suspend fun search(query: InstrumentSearchQuery): InstrumentSearchState {
        val generation = requestGeneration.incrementAndGet()
        if (query.normalizedText.isEmpty()) {
            return InstrumentSearchState.Empty(query).also { mutableState.value = it }
        }

        val previous = mutableState.value
        mutableState.value = InstrumentSearchState.Loading(query)

        val next = try {
            val page = client.search(query)
            if (page.items.isEmpty()) {
                InstrumentSearchState.Empty(query, page.catalog_version, page.as_of)
            } else {
                InstrumentSearchState.Results(
                    query = query,
                    instruments = page.items,
                    nextCursor = page.next_cursor,
                    catalogVersion = page.catalog_version,
                    asOf = page.as_of,
                )
            }
        } catch (cancelled: CancellationException) {
            if (requestGeneration.get() == generation) mutableState.value = previous
            throw cancelled
        } catch (error: InstrumentSearchApiException) {
            InstrumentSearchState.Error(
                query = query,
                code = error.code,
                message = error.message,
                retryable = error.retryable,
                httpStatus = error.httpStatus,
            )
        } catch (_: IOException) {
            InstrumentSearchState.Error(
                query = query,
                code = "network_error",
                message = "Unable to reach the Kuber service",
                retryable = true,
            )
        } catch (_: RuntimeException) {
            InstrumentSearchState.Error(
                query = query,
                code = "unexpected_error",
                message = "Instrument search is temporarily unavailable",
                retryable = false,
            )
        }

        if (requestGeneration.get() == generation) mutableState.value = next
        return next
    }

    fun reset() {
        requestGeneration.incrementAndGet()
        mutableState.value = InstrumentSearchState.Idle
    }
}
