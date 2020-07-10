package com.mycode.kotlinsingle

import kotlin.reflect.KProperty

/**
 * Delegate to create extension properties tied to State
 *
 * @param T the property type (inferred)
 * @property value default initial code
 */
class StateProperty <T : Any> (var value : T) {
    operator fun getValue(thisRef: State, property: KProperty<*>) : T {
        return value
    }

    operator fun setValue(thisRef: State, property: KProperty<*>, value: T) {
        this.value = value
    }
}