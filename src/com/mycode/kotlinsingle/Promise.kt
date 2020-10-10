package com.mycode.kotlinsingle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Represents a promise tied to an event which resolves on the event thread
 *
 */
class Promise<T : Any> (private val tag: String){
    private enum class PromiseState { PENDING, RESOLVING, RESOLVED }
    private var state : PromiseState = PromiseState.PENDING
    private lateinit var onResolve: (T, State, Promise<T>)->Unit
    private lateinit var nextPromise : Promise<T>
    private lateinit var result : T
    private lateinit var previousPromise : Promise<T>
    private var lifecycleOwner: LifecycleOwner? = null
    var isLifecycleDestroyed = false

    //resolves the promise chain
    @Synchronized
    private fun handleResult() {
        when(state) {
            PromiseState.RESOLVING -> {
                //if 'then' continuation already set
                if(::onResolve.isInitialized) {
                    state = PromiseState.RESOLVED
                    //trigger a promise event to resolve on the subscriber or UI tread
                    publish(EventPromiseResolve(tag, lifecycleOwner, this) {
                        onResolve(result, StateInstance.state, nextPromise)
                    })
                }
            }
            PromiseState.PENDING, PromiseState.RESOLVED -> {
                //do nothing
            }
        }
    }

    /**
     * Resolves the promise "then" on the event thread
     *
     * @param value the return value to pass on to the "then" handler
     */
    fun resolve(value : T) {
        result = value
        state = PromiseState.RESOLVING
        handleResult()
    }

    /**
     * Repeat the promise resolution again to emulate a loop
     *
     * @param value the return value to pass on in the next loop
     */
    fun again(value : T) {
        if(!::previousPromise.isInitialized && (lifecycleOwner != null)) {
            throw Exception("Again() must be called in 'then' continuation only")
        }

        publish(EventPromiseResolve(tag, null, this) {
            //call again the previous continuation
            previousPromise.onResolve(value, StateInstance.state, this)
        })
    }

    /**
     * Registers a handler to trigger when this promise resolves
     *
     * @param onResolve the handler
     * @return the next promise in the promise chain
     */
    fun then(onResolve : (T, State, Promise<T>)->Unit) : Promise<T> {
        nextPromise = Promise(tag)
        nextPromise.previousPromise = this
        this.onResolve = onResolve

        handleResult()

        return nextPromise
    }

    /**
     * Registers a handler running on the lifecycleOwner scope, triggered when this promise resolves
     *
     * @param lifecycleOwner the lifecycleOwner the handler runs on
     * @param onResolve the handler
     */
    fun thenUpdateUI(lifecycleOwner: LifecycleOwner, onResolve : (T)->Unit) {
        nextPromise = Promise(tag)
        nextPromise.previousPromise = this
        this.lifecycleOwner = lifecycleOwner
        this.onResolve = { result, _, _ -> onResolve(result) }

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    if(event == Lifecycle.Event.ON_DESTROY) {
                        //indicate destroyed so any pending promises will not be resolved
                        isLifecycleDestroyed = true
                    }
                }
            })
        }

        handleResult()
    }
}