package com.mycode.kotlinsingle

/**
 * Represents an application event that includes a promise to resolve when the event finishes
 */
abstract class EventPromise<T : Any> : Event() {
    internal lateinit var promise : Promise<T>

    /**
     * Resolves this event and triggers the 'then' continuation of the publisher
     *
     * @param value any value to pass as parameter to the continuation
     */
    fun resolve(value : T) {
        promise.resolve(value)
    }
}