/*
 * Copyright (c) 2018 Otalia Studios. Author: Mattia Iavarone.
 */

package com.otaliastudios.firestore

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Keep
import androidx.databinding.BaseObservable
import com.google.firebase.firestore.Exclude
import java.util.LinkedHashMap
import kotlin.reflect.KProperty

/**
 * A map implementation. Delegates to a mutable map.
 * Introduce dirtyness checking for childrens.
 */
@Keep
open class FirestoreMap<T>(
        source: Map<String, T>? = null
) : BaseObservable(), /*MutableMap<String, T> by data,*/ Parcelable {

    private val data: MutableMap<String, T> = mutableMapOf()
    private val dirty: MutableSet<String> = mutableSetOf()

    @get:Exclude
    val keys get() = data.keys

    @get:Exclude
    val size get() = data.size

    init {
        if (source != null) {
            mergeValues(source, false, "Initialization")
        }
    }

    internal fun isDirty(): Boolean {
        return keys.any { isDirty(it) }
    }

    internal fun isDirty(key: String): Boolean {
        if (dirty.contains(key)) return true
        if (key.contains('.')) {
            val first = key.split('.')[0]
            val second = key.removePrefix("$first.")
            val data = get(first)
            return when (data) {
                null -> false
                is FirestoreMap<*> -> data.isDirty(second)
                else -> throw IllegalArgumentException("Accessing with dot notation, but it is not a FirestoreMap.")
            }
        }
        val what = get(key)
        if (what is FirestoreList<*>) {
            return what.isDirty()
        } else {
            return false
        }
    }

    internal fun clearDirt() {
        for (key in keys) {
            clearDirt(key)
        }
    }

    internal fun clearDirt(key: String) {
        if (dirty.contains(key)) {
            dirty.remove(key)
        } else {
            val value = get(key)
            if (value is FirestoreMap<*>) {
                value.clearDirt()
            } else if (value is FirestoreList<*>) {
                value.clearDirt()
            }
        }
    }


    private fun <K> createFirestoreMap(key: String): FirestoreMap<K> {
        val map = try { onCreateFirestoreMap<K>(key) } catch (e: Exception) {
            FirestoreMap<K>()
        }
        map.clearDirt()
        return map
    }

    private fun <K: Any> createFirestoreList(key: String): FirestoreList<K> {
        val list = try { onCreateFirestoreList<K>(key) } catch (e: Exception) {
            FirestoreList<K>()
        }
        list.clearDirt()
        return list
    }

    protected open fun <K> onCreateFirestoreMap(key: String): FirestoreMap<K> {
        val provider = FirestoreDocument.metadataProvider(this::class)
        var candidate = provider.create<FirestoreMap<K>>(key)
        candidate = candidate ?: provider.createInnerType()
        candidate = candidate ?: FirestoreMap()
        return candidate
    }

    protected open fun <K: Any> onCreateFirestoreList(key: String): FirestoreList<K> {
        val provider = FirestoreDocument.metadataProvider(this::class)
        var candidate = provider.create<FirestoreList<K>>(key)
        candidate = candidate ?: provider.createInnerType()
        candidate = candidate ?: FirestoreList()
        return candidate
    }

    final operator fun set(key: String, value: T) {
        val result = onSet(key, value)
        /* if (result == null) {
            // Do nothing.
        } else */if (key.contains('.')) {
            val first = key.split('.')[0]
            val second = key.removePrefix("$first.")
            val data = getOrCreateFirestoreMap(first)
            data[second] = result
        } else {
            data[key] = result
            dirty.add(key)
            val resource = FirestoreDocument.metadataProvider(this::class).getBindableResource(key)
            if (resource != null) notifyPropertyChanged(resource)
        }
    }

    internal open fun onSet(key: String, value: T): T = value

    final operator fun get(key: String): T? {
        return if (key.contains('.')) {
            val first = key.split('.')[0]
            val second = key.removePrefix("$first.")
            val data = getOrCreateFirestoreMap(first)
            data[second]
        } else {
            data[key]
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getOrCreateFirestoreMap(key: String): FirestoreMap<T> {
        val data = get(key)
        if (data == null) {
            val map = createFirestoreMap<T>(key)
            set(key, map as T)
            return map
        } else if (data is FirestoreMap<*>) {
            return data as FirestoreMap<T>
        } else {
            throw RuntimeException("Trying to access map with dot notation, " +
                    "but it is not a FirestoreMap. key: $key, value: $data")
        }
    }

    /**
     * This is called when using the delegated property (by this).
     * By using source[name], we will actually trigger the [get] method.
     *
     * As an extra feature, this function will also try to instantiate the item
     * if possible. This means that we only offer this functionality for NON-NULLABLE, DECLARED
     * fields, which totally makes sense.
     *
     */
    protected operator fun <R: T> getValue(source: FirestoreMap<T>, property: KProperty<*>): R {
        @Suppress("UNCHECKED_CAST")
        var what = source[property.name] as R

        if (what == null) {
            val provider = FirestoreDocument.metadataProvider(this::class)
            if (!provider.isNullable(property.name)) {
                what = provider.create<R>(property.name)!!
                source[property.name] = what
                // We don't want this to be dirty now! It was just retrieved, not really set.
                // If we leave it dirty, it would not be updated on next mergeValues().
                clearDirt(property.name)
            }
        }
        return what
    }


    /**
     * This is called when using the delegated property (by this).
     * By using source[name], we will actually trigger the [set] method.
     */
    protected operator fun <R: T> setValue(source: FirestoreMap<T>, property: KProperty<*>, what: R) {
        source[property.name] = what
    }

    /**
     * Returns a map that collects all the values in this [FirestoreMap].
     * Filters only dirty values if needed, and flattens [FirestoreMap]s and [FirestoreList]s
     * into real [Map] and [List] values.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun collectValues(dirtyOnly: Boolean): Map<String, T?> {
        val map = mutableMapOf<String, T?>()
        for (key in keys) {
            val child = get(key)
            if (child is FirestoreMap<*>) {
                val childMap = child.collectValues(dirtyOnly)
                if (childMap.isNotEmpty()) map[key] = childMap as T?
            } else if (child is FirestoreList<*>) {
                val childList = child.collectValues(dirtyOnly)
                if (childList.isNotEmpty()) map[key] = childList as T?
            } else if (!dirtyOnly || dirty.contains(key)) {
                map[key] = child
            }
        }
        return map
    }

    /**
     * Flattens the values inside this map, with the given prefix for our own keys.
     * The final map will have all fields (only dirty if specified) at the base level.
     *
     *
     */
    internal fun flattenValues(map: MutableMap<String, Any?>, prefix: String, dirtyOnly: Boolean) {
        for (key in keys) {
            val child = get(key)
            val childPrefix = "$prefix.$key".trim('.')
            if (child is FirestoreMap<*>) {
                child.flattenValues(map, childPrefix, dirtyOnly)
            } else if (child is FirestoreList<*>) {
                child.flattenValues(map, childPrefix, dirtyOnly)
            } else if (!dirtyOnly || dirty.contains(key)) {
                map[childPrefix] = child
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun mergeValues(values: Map<String, T>, checkChanges: Boolean, tag: String): Boolean {
        var changed = false
        for ((key, value) in values) {
            FirestoreLogger.v("$tag mergeValues: key $key with value $value, dirty: ${isDirty(key)}")
            if (isDirty(key)) continue
            if (value is Map<*, *> && value.keys.all { it is String }) {
                val child = get(key) ?: createFirestoreMap<Any?>(key) as T // T
                data[key] = child
                child as FirestoreMap<Any?>
                value as Map<String, Any?>
                val childChanged = child.mergeValues(value, checkChanges && !changed, tag)
                changed = changed || childChanged
            } else if (value is List<*>) {
                val child = get(key) ?: createFirestoreList<Any>(key) as T // T
                data[key] = child
                child as FirestoreList<Any>
                value as List<Any>
                val childChanged = child.mergeValues(value, checkChanges && !changed, tag)
                changed = changed || childChanged
            } else {
                if (checkChanges && !changed) {
                    FirestoreLogger.v("$tag mergeValues: key $key comparing with value ${data[key]}")
                    changed = changed || value != data[key]
                }
                data[key] = value
            }
        }
        return changed
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is FirestoreMap<*> &&
                other.data.size == data.size &&
                other.data.all { it.value == data[it.key] } &&
                other.dirty.size == dirty.size &&
                other.dirty.containsAll(dirty)
        // TODO it's better to collect the dirty keys, though it makes everything slow.
    }

    override fun hashCode(): Int {
        var result = data.hashCode()
        result = 31 * result + dirty.hashCode()
        return result
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        val hashcode = hashCode()
        parcel.writeInt(hashcode)

        // Write class name
        FirestoreLogger.v("Map $hashcode: writing class ${this::class.java.name}")
        parcel.writeString(this::class.java.name)

        // Write dirty data
        FirestoreLogger.v("Map $hashcode: writing dirty count ${dirty.size} and dirty keys ${dirty.toTypedArray().joinToString()} ${dirty.toTypedArray().size}.")
        parcel.writeInt(dirty.size)
        parcel.writeStringArray(dirty.toTypedArray())

        FirestoreLogger.v("Map $hashcode: writing data size. $size")
        parcel.writeInt(size)
        for ((key, value) in data) {
            parcel.writeString(key)
            FirestoreLogger.v("Map $hashcode: writing value for key $key...")
            FirestoreParcelers.write(parcel, value, hashcode.toString())
        }

        val bundle = Bundle()
        onWriteToBundle(bundle)
        FirestoreLogger.v("Map $hashcode: writing extra bundle. Size is ${bundle.size()}")
        parcel.writeBundle(bundle)
    }

    companion object {

        @Suppress("unused")
        @JvmField
        public val CREATOR = object : Parcelable.ClassLoaderCreator<FirestoreMap<Any?>> {

            override fun createFromParcel(source: Parcel): FirestoreMap<Any?> {
                // This should never be called by the framework.
                FirestoreLogger.e("Map: received call to createFromParcel without classLoader.")
                return createFromParcel(source, FirestoreMap::class.java.classLoader!!)
            }

            @Suppress("UNCHECKED_CAST")
            override fun createFromParcel(parcel: Parcel, loader: ClassLoader): FirestoreMap<Any?> {
                val hashcode = parcel.readInt()

                // Read class and create the map object.
                val klass = Class.forName(parcel.readString()!!)
                FirestoreLogger.v("Map $hashcode: read class ${klass.simpleName}")
                val firestoreMap = klass.newInstance() as FirestoreMap<Any?>

                // Read dirty data
                val dirty = Array(parcel.readInt()) { "" }
                parcel.readStringArray(dirty)
                FirestoreLogger.v("Map $hashcode: read dirty count ${dirty.size} and array ${dirty.joinToString()}")

                // Read actual data
                val count = parcel.readInt()
                FirestoreLogger.v("Map $hashcode: read data size $count")

                val values = HashMap<String, Any?>(count)
                repeat(count) {
                    val key = parcel.readString()!!
                    FirestoreLogger.v("Map $hashcode: reading value for key $key...")
                    values[key] = FirestoreParcelers.read(parcel, loader, hashcode.toString())
                }

                // Set both
                firestoreMap.dirty.clear()
                firestoreMap.dirty.addAll(dirty)
                firestoreMap.data.clear()
                firestoreMap.data.putAll(values)

                // Read the extra bundle
                FirestoreLogger.v("Map $hashcode: reading extra bundle.")
                val bundle = parcel.readBundle(loader)
                FirestoreLogger.v("Map $hashcode: read extra bundle, size ${bundle.size()}")
                firestoreMap.onReadFromBundle(bundle!!)
                return firestoreMap
            }

            override fun newArray(size: Int): Array<FirestoreMap<Any?>?> {
                return Array(size) { null }
            }
        }
    }

    protected open fun onWriteToBundle(bundle: Bundle) {}

    protected open fun onReadFromBundle(bundle: Bundle) {}
}