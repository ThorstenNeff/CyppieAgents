package com.tneff.cyppieagents.connect

/** Remote mode is jvm-only in the MVP → an in-memory ack store here (the durable reload-safe path is jvm). */
actual fun defaultDeviceCodesAckStore(): DeviceCodesAckStore = InMemoryDeviceCodesAckStore()
