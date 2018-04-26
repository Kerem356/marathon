package com.malinskiy.marathon.execution

import com.malinskiy.marathon.device.DevicePoolId
import com.malinskiy.marathon.device.DeviceProvider
import com.malinskiy.marathon.execution.strategy.PoolingStrategy
import com.malinskiy.marathon.healthCheck
import com.malinskiy.marathon.test.Test
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.launch
import mu.KotlinLogging


/**
 * The logic of scheduler
 *
 * 1. Pooling:      Create pools of devices
 * 2. Sharding:     Define sharding (creates device-test association)
 * 3. Flakiness:    Add known retries to tests in all shards
 * 4. Sorting:      Sort all tests
 * 5. Batching:     TestBatch into manageable chunks
 * 6. Retries:      Retry if something fails and we didn't account for it in the flakiness
 */
class Scheduler(private val deviceProvider: DeviceProvider,
                private val poolingStrategy: PoolingStrategy,
                private val configuration: Configuration,
                private val list: Collection<Test>) {

    private val logger = KotlinLogging.logger("DynamicPoolFactory")

    private val pools = mutableMapOf<DevicePoolId, SendChannel<PoolMessage>>()

    suspend fun execute() {
        subscribeOnDevices()
        healthCheck(10_000, 1_000) {
            !pools.values.all { it.isClosedForSend }
        }.join()
    }


    private fun subscribeOnDevices() {
        launch {
            for (msg in deviceProvider.subscribe()) {
                when (msg) {
                    is DeviceProvider.DeviceEvent.DeviceConnected -> {
                        onDeviceConnected(msg)
                    }
                    is DeviceProvider.DeviceEvent.DeviceDisconnected -> {
                        onDeviceDisconnected(msg)
                    }
                }
            }
        }
    }

    private suspend fun onDeviceDisconnected(item: DeviceProvider.DeviceEvent.DeviceDisconnected) {
        pools.values.forEach {
            it.send(PoolMessage.RemoveDevice(item.device))
        }
    }

    private suspend fun onDeviceConnected(item: DeviceProvider.DeviceEvent.DeviceConnected) {
        val poolId = poolingStrategy.associate(item.device)
        pools.computeIfAbsent(poolId, { id -> PoolTestExecutor(id.name, configuration, list) })
        pools[poolId]?.send(PoolMessage.AddDevice(item.device))
    }
}