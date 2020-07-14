package com.mycode.kotlinsingle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

/**
 * Represents an application event
 */
abstract class Event {
    /**
     * Represents a subscriber
     *
     * @property eventClass class of the event this subscriber subscribes to
     * @property promiseType type of the promise return value
     * @property callback callback to call when the event is published
     * @property promise promise to fulfill when the event is resolved
     */
     class Subscriber(val eventClass : KClass<*>, val callback : Any, private val promiseType : KClass<*>, val promise : Promise<*>)

    /**
     * Represents a chain of pending events to trigger
     *
     * @property eventClass class of the event
     * @property event the event to trigger
     * @property next the next event chain to trigger after this
     */
    class EventChain(val eventClass : KClass<*>, val event : Event, var next : EventChain?)

    companion object {
        val subscriberList = ArrayList<Subscriber?>() //list of subscribers
        var currentEvent : EventChain? = null //current event to trigger
        lateinit var latestEvent : EventChain //latest event published
        val eventChainMutex = Mutex() //synchronizes access to the event chain
        val subscriberGroup = HashMap<String, ArrayList<Int>>() //map of subscriber groups
        var logger : ((KClass<*>, Event, State, String) -> Unit)? = null //logger to log events
    }
}

private fun addSubscriberToGroup(subscriberGroupName : String, index : Int) {
    if(Event.subscriberGroup.containsKey(subscriberGroupName)) {
        Event.subscriberGroup[subscriberGroupName]?.add(index)
    } else {
        with(ArrayList<Int>()) {
            add(index)
            Event.subscriberGroup[subscriberGroupName] = this
        }
    }
}

//subscribes a callback to an event and creates a promise for the subscriber
fun <T1, T2 : Any> doSubscribe (eventClass : KClass<*>, subscriberGroupName : String?, lifecycleOwner: LifecycleOwner?, promiseType : KClass<*>, callback : (T1, State, Promise<T2>) -> Unit) : Promise<T2> {
    //publish event to subscribe later
    return Promise<T2>(eventClass.simpleName ?: "unknown").also {
        publish(EventSubscribe(eventClass, subscriberGroupName, lifecycleOwner, promiseType, callback, it))
    }
}

//subscribes a callback to an event
fun <T> doSubscribe (eventClass : KClass<*>, subscriberGroupName : String?, lifecycleOwner: LifecycleOwner?, callback : (T, State, Promise<*>) -> Unit)  {
    //publish event to subscribe later
    return publish(EventSubscribe(eventClass, subscriberGroupName, lifecycleOwner, Unit::class, callback, Promise<Unit>(eventClass.simpleName ?: "unknown")))
}

/**
 * Subscribes a callback to this event
 *
 * @param T1 the type of event to subscribe too
 * @param T2 the type of the promise return value
 * @param callback callback to call when this event occurs
 * @return promise to fulfill when an asynchronous task is done, the 'then' continuation executes in the event thread
 */
inline fun <reified T1 : Event, reified T2 : Any> subscribeWithPromise(noinline callback : (T1, State, Promise<T2>) -> Unit) : Promise<T2> {
    return doSubscribe(T1::class, null, null, T2::class, callback)
}

/**
 * Subscribes a callback to this event
 *
 * @param T1 the type of event to subscribe too
 * @param T2 the type of the promise return value
 * @param subscriberGroupName the group name this subsriber will belong too
 * @param callback callback to call when this event occurs
 * @return promise to fulfill when an asynchronous task is done, the 'then' continuation executes in the event thread
 */
inline fun <reified T1 : Event, reified T2 : Any> subscribeWithPromise(subscriberGroupName : String, noinline callback : (T1, State, Promise<T2>) -> Unit) : Promise<T2> {
    return doSubscribe(T1::class, subscriberGroupName, null, T2::class, callback)
}

/**
 * Subscribes a callback to this event
 *
 * @param T they type of event to subscribe too
 * @param callback callback to call when this event occurs
 */
inline fun <reified T : Event> subscribe(noinline callback : (T, State) -> Unit) {
    doSubscribe(T::class, null, null) { event : T, state, _ ->
        callback(event, state)
    }
}

/**
 * Subscribes a callback to this event
 *
 * @param T they type of event to subscribe too
 * @param subscriberGroupName the group name this subsriber will belong too
 * @param callback callback to call when this event occurs
 */
inline fun <reified T : Event> subscribe(subscriberGroupName : String, noinline callback : (T, State) -> Unit) {
    doSubscribe(T::class, subscriberGroupName, null) { event : T, state, _ ->
        callback(event, state)
    }
}

/**
 * Subscribes a callback to this event
 *
 * @param T they type of event to subscribe too
 * @param lifecycleOwner the lifecycleOwner this subscriber launches its callback on
 * @param callback callback to call when this event occurs
 */
inline fun <reified T : Event> subscribeUI(lifecycleOwner: LifecycleOwner, noinline callback : suspend (T) -> Unit) {
    doSubscribe(T::class, null, lifecycleOwner) { event : T, _, _ ->
        //run callback on UI thread
        lifecycleOwner.lifecycleScope.launch {
            callback(event)
        }
    }
}

/**
 * Unsubscribes a subscriber callback
 *
 * @param subscriberGroupName name of subscriber group to unsubscribe
 */
fun unsubscribe(subscriberGroupName : String) {
    //publish an event to remove the subscriber group later
    publish(EventUnsubscribeGroup(subscriberGroupName))
}

fun removeSubscriberGroup(subscriberGroupName : String) {
    Event.subscriberGroup[subscriberGroupName]?.let { indexList ->
        indexList.forEach {
            Event.subscriberList[it] = null
        }
    }

    Event.subscriberGroup.remove(subscriberGroupName)
}

fun processEvent(currentEvent: Event.EventChain) {
    Event.logger?.let {
        with(currentEvent) {
            val info = when(eventClass) {
                EventPromiseResolve::class -> "Promise resolved: " + (event as EventPromiseResolve).tag
                EventSubscribe::class -> {
                    event as EventSubscribe
                    "Subscribe: " + event.eventClass.simpleName + if(event.subscriberGroupName == null) "" else " Group: " + event.subscriberGroupName
                }
                EventUnsubscribe::class -> "Unsubscribe"
                EventUnsubscribeGroup::class -> "UnsubscribeGroup: " + (event as EventUnsubscribeGroup).subscriberGroupName
                else -> eventClass.simpleName ?: ""
            }

            //log the event
            it(eventClass, event, StateInstance.state, info)
        }
    }

    //for promise events execute the handler
    if(currentEvent.eventClass == EventPromiseResolve::class) {
        (currentEvent.event as EventPromiseResolve).let {
            if(it.lifecycleOwner == null) {
                //resolve on subscriber thread
                it.handler()
            } else {
                //resolve on UI thread
                it.lifecycleOwner.lifecycleScope.launch {
                    it.handler()
                }
            }
        }
    } else if(currentEvent.eventClass == EventSubscribe::class) {
        //for subscribe events subscribe to the group
        with(currentEvent.event as EventSubscribe) {
            var index = -1

            //look for a free space in the list to insert the subscriber into
            for((i, eventCallback) in Event.subscriberList.withIndex()) {
                if(eventCallback == null) {
                    index = i
                    Event.subscriberList[index] = Event.Subscriber(eventClass, callback, promiseType, promise)
                    break
                }
            }

            //if no free space add to the end
            if(index == -1) {
                index = Event.subscriberList.size
                Event.subscriberList.add(Event.Subscriber(eventClass, callback, promiseType, promise))
            }

            //add to subscriber group if given
            subscriberGroupName?.let {
                addSubscriberToGroup(it, index)
            }

            //if subscribed from UI and lifecycle becomes destroyed, then automatically unsubscribe
            lifecycleOwner?.let {
                it.lifecycleScope.launch {
                    it.lifecycle.addObserver(object : LifecycleEventObserver {
                        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                            if(event == Lifecycle.Event.ON_DESTROY) {
                                publish(EventUnsubscribe(index))
                            }
                        }
                    })
                }
            }
        }
    } else if(currentEvent.eventClass == EventUnsubscribe::class) {
        //for unsubscribe events unsubscribe the subscriber
        Event.subscriberList[(currentEvent.event as EventUnsubscribe).index] = null
    } else if(currentEvent.eventClass == EventUnsubscribeGroup::class) {
        //for unsubscribe group events unsubscribe the group
        removeSubscriberGroup((currentEvent.event as EventUnsubscribeGroup).subscriberGroupName)
    } else {
        //for normal events trigger the subscribers
        Event.subscriberList.forEach {
            if (it != null && it.eventClass == currentEvent.eventClass) {
                @Suppress("UNCHECKED_CAST")
                (it.callback as (Event, State, Promise<*>) -> Unit)(
                    currentEvent.event,
                    StateInstance.state,
                    it.promise
                )
            }
        }
    }
}

fun <T : Event> doPublish(eventClass : KClass<*>, event : T) {
    runBlocking {
        //check if any event chain is ongoing, and link event to that chain otherwise start a new chain
        Event.eventChainMutex.withLock {
            if(Event.currentEvent == null) {
                Event.EventChain(eventClass, event, null).let {
                    Event.currentEvent = it
                    Event.latestEvent = it
                }

                //keep executing events in the chain in a worker thread
                GlobalScope.launch {
                    while(Event.currentEvent != null) {
                        Event.currentEvent?.let {
                            processEvent(it)

                            Event.eventChainMutex.withLock {
                                Event.currentEvent = it.next
                            }
                        }
                    }
                }
            } else {
                Event.EventChain(eventClass, event, null).let {
                    Event.latestEvent.next = it
                    Event.latestEvent = it
                }
            }
        }
    }
}

//function to add promise to event since inline function cannot access the private setter
fun <T : Any> addPromiseToEvent(eventPromise: EventPromise<T>, promise: Promise<T>) {
    eventPromise.promise = promise
}

/**
 * Publishes an Event to all subscribers
 *
 * @param T the type of the event (inferred)
 * @param event the event to publish
 */
inline fun <reified T : Event> publish(event : T) {
    doPublish(T::class, event)
}

/**
 * Publishes an EventPromise to all subscribers
 *
 * @param T1 the type of the promise return value (inferred)
 * @param T2 the type of the event (inferred)
 * @param eventPromise the EventPromise to publish
 * @return the promise to be resolved by subscribers
 */
inline fun <T1 : Any, reified T2 : EventPromise<T1>> publish(eventPromise : T2) : Promise<T1> {
    return Promise<T1>(T2::class.simpleName ?: "unknown").also {
        addPromiseToEvent(eventPromise, it)
        doPublish(T2::class, eventPromise)
    }
}