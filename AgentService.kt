import kotlinx.coroutines.*

class AgentService {
    suspend fun pollLoop() {
        coroutineScope {
            while (isActive) {
                launch { // moved to coroutineScope
                    // your processing code here
                }
            }
        }
    }
}