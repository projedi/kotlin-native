package org.jetbrains.kotlin.backend.konan.llvm

import llvm.LLVMLinkage
import llvm.LLVMSetLinkage

// NOTE: Must match HashMap from runtime.
private class StaticHashMap<K, V> private constructor(
        private var hashFunction: (K) -> Int,
        private var keysArray: ArrayList<K>,
        private var valuesArray: ArrayList<V>?, // allocated only when actually used, always null in pure HashSet
        private var presenceArray: IntArray,
        private var hashArray: IntArray
) {
    var maxProbeDistance: Int = INITIAL_MAX_PROBE_DISTANCE
        private set

    var hashShift: Int = computeShift(hashSize)
        private set

    val keys: List<K>
        get() = keysArray

    val values: List<V>?
        get() = valuesArray

    val length: Int
        get() = keysArray.size

    val presence: IntArray
        get() = presenceArray

    val hashes: IntArray
        get() = hashArray

    constructor(keys: List<K>, values: List<V>?, hashFunction: (K) -> Int) : this(
            hashFunction,
            ArrayList<K>(keys.size),
            if (values != null) ArrayList<V>(keys.size) else null,
            IntArray(keys.size),
            IntArray(computeHashSize(keys.size))) {
        for ((i, key) in keys.withIndex()) {
            val index = addKey(key)
            if (values != null) {
                if (index < 0) {
                    valuesArray!![-index - 1] = values[i]
                } else {
                    valuesArray!!.add(values[i])
                }
            }
        }
    }

    private val hashSize: Int get() = hashArray.size

    private fun hash(key: K) = (hashFunction(key) * MAGIC) ushr hashShift

    private fun rehash(newHashSize: Int) {
        hashArray = IntArray(newHashSize)
        hashShift = computeShift(newHashSize)
        var i = 0
        while (i < keysArray.size) {
            if (!putRehash(i++)) {
                throw IllegalStateException("This cannot happen with fixed magic multiplier and grow-only hash array. " +
                        "Have object hashCodes changed?")
            }
        }
    }

    private fun putRehash(i: Int): Boolean {
        var hash = hash(keysArray[i])
        var probesLeft = maxProbeDistance
        while (true) {
            val index = hashArray[hash]
            if (index == 0) {
                hashArray[hash] = i + 1
                presenceArray[i] = hash
                return true
            }
            if (--probesLeft < 0) return false
            if (hash-- == 0) hash = hashSize - 1
        }
    }

    internal fun addKey(key: K): Int {
        retry@ while (true) {
            var hash = hash(key)
            // put is allowed to grow maxProbeDistance with some limits (resize hash on reaching limits)
            val tentativeMaxProbeDistance = (maxProbeDistance * 2).coerceAtMost(hashSize / 2)
            var probeDistance = 0
            while (true) {
                val index = hashArray[hash]
                if (index <= 0) { // claim or reuse hash slot
                    val putIndex = keysArray.size
                    keysArray.add(key)
                    presenceArray[putIndex] = hash
                    hashArray[hash] = putIndex + 1
                    if (probeDistance > maxProbeDistance) maxProbeDistance = probeDistance
                    return putIndex
                }
                if (keysArray[index - 1] == key) {
                    return -index
                }
                if (++probeDistance > tentativeMaxProbeDistance) {
                    rehash(hashSize * 2) // cannot find room even with extra "tentativeMaxProbeDistance" -- grow hash
                    continue@retry
                }
                if (hash-- == 0) hash = hashSize - 1
            }
        }
    }

    private companion object {
        const val MAGIC =  -1640531527 // 2654435769L.toInt(), golden ratio
        const val INITIAL_MAX_PROBE_DISTANCE = 2

        @UseExperimental(ExperimentalStdlibApi::class)
        fun computeHashSize(capacity: Int): Int = (capacity.coerceAtLeast(1) * 3).takeHighestOneBit()

        @UseExperimental(ExperimentalStdlibApi::class)
        fun computeShift(hashSize: Int): Int = hashSize.countLeadingZeroBits() + 1
    }
}

internal fun StaticData.hashMapLiteral(keys: List<String>, values: List<String>): ConstPointer {
    // NOTE: This presumes that String is UTF16LE and it's hash function is cityHash64.
    val resultingMap = StaticHashMap(keys, values) {
        val res = cityHash64(it.toByteArray(Charsets.UTF_16LE))
        val resInt = res.toInt()
        println("hash of $it is $resInt (64 is $res)")
        resInt
    }

    val valueStrBuilder = StringBuilder("${resultingMap.length}{")
    for (i in resultingMap.keys.indices) {
        valueStrBuilder.append('"')
        valueStrBuilder.append(resultingMap.keys[i])
        valueStrBuilder.append("\":\"")
        valueStrBuilder.append(resultingMap.values!![i])
        valueStrBuilder.append("\",")
    }
    valueStrBuilder.append('}')
    val valueStr = valueStrBuilder.toString()

    return hashMapLiterals.getOrPut(valueStr) {
        val keysArray = ArrayList<ConstPointer>(resultingMap.keys.size)
        for (key in resultingMap.keys) {
            keysArray.add(kotlinStringLiteral(key))
        }
        val valuesArray = ArrayList<ConstPointer>(resultingMap.values!!.size)
        for (value in resultingMap.values!!) {
            valuesArray.add(kotlinStringLiteral(value))
        }

        val objRef = createConstHashMap(
                createConstKotlinArray(context.ir.symbols.array.owner, keysArray),
                createConstKotlinArray(context.ir.symbols.array.owner, valuesArray),
                createConstKotlinArray(context.ir.symbols.intArray.owner, resultingMap.presence.map { Int32(it) } ),
                createConstKotlinArray(context.ir.symbols.intArray.owner, resultingMap.hashes.map { Int32(it) } ),
                resultingMap.maxProbeDistance,
                resultingMap.length,
                resultingMap.hashShift)
        val name = "khashmap:" + valueStr.globalHashBase64

        val res = createAlias(name, objRef)
        LLVMSetLinkage(res.llvm, LLVMLinkage.LLVMWeakAnyLinkage)
        res
    }
}

internal fun StaticData.hashSetLiteral(keys: List<String>): ConstPointer {
    // NOTE: This presumes that String is UTF16LE and it's hash function is cityHash64.
    val resultingMap = StaticHashMap<String, Unit>(keys, null) { cityHash64(it.toByteArray(Charsets.UTF_16LE)).toInt() }

    val valueStrBuilder = StringBuilder("${resultingMap.length}[")
    for (i in resultingMap.keys.indices) {
        valueStrBuilder.append('"')
        valueStrBuilder.append(resultingMap.keys[i])
        valueStrBuilder.append("\",")
    }
    valueStrBuilder.append(']')
    val valueStr = valueStrBuilder.toString()

    return hashMapLiterals.getOrPut(valueStr) {
        val keysArray = ArrayList<ConstPointer>(resultingMap.keys.size)
        for (key in resultingMap.keys) {
            keysArray.add(kotlinStringLiteral(key))
        }

        val hashMap = createConstHashMap(
                createConstKotlinArray(context.ir.symbols.array.owner, keysArray),
                null,
                createConstKotlinArray(context.ir.symbols.intArray.owner, resultingMap.presence.map { Int32(it) } ),
                createConstKotlinArray(context.ir.symbols.intArray.owner, resultingMap.hashes.map { Int32(it) } ),
                resultingMap.maxProbeDistance,
                resultingMap.length,
                resultingMap.hashShift)
        val objRef = createConstHashSet(hashMap)

        val name = "khashset:" + valueStr.globalHashBase64

        val res = createAlias(name, objRef)
        LLVMSetLinkage(res.llvm, LLVMLinkage.LLVMWeakAnyLinkage)
        res
    }
}
