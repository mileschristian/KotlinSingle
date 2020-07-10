package com.mycode.kotlinsingle

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