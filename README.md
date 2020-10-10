# KotlinSingle

Android event bus for synchronized state management on a single thread.

## Usage

Create classes to hold application state then add them as extension properties to the 'State' object using the 'StateProperty' delegation

```kotlin
//holds state of our counter
class CounterState {
    var count = 0
}

//provides access to the counter state through the counter property
State.counter by StateProperty(CounterState())
```

Create events to read or modify state by extending the 'Event' class

```kotlin
//event used to increment the counter
class EventIncrementCount(val amount: Int) : Event()

//event used to indicate counter has changed
class EventCountChanged(val count: Int) : Event()
```

Publish or Subscribe to events to read or modify state

```kotlin
//increments the counter whenever EventIncrementCount is published
subscribe<EventIncrementCount> { event, state ->
    state.counter.count += event.amount
    publish(EventCountChanged(state.counter.count)) //inform that count has changed
}

//updates the UI with the new count value (callback runs on the UI thread)
subscribeUI<EventCountChanged>(MyActivity) { event ->
    myEditText.text = event.count.toString()
}

//request to increment the counter by 1
publish(EventIncrementCount(1))
```

## Synchronized state access

State can only be access through subscriber callbacks.  All subscriber callbacks execute one after another on a single thread.  As long as state is stricly accessed only in subscribers, all state access will be synchronized.

```kotlin
//here we increment the counter 100 times in 5 threads running simultaneously
1.rangeTo(5).map { 
    GlobalScope.launch {
        for(i in 1...100) {
            publish(EventIncrementCount(1))
        }
    }
}

/*
Final count will always be 500 because even if events trigger simultaneously they are handled one after another.
*/
```
In order to maintain this synchronization some rules must be followed:

1. Always access/modify state only in subscriber callbacks or 'then' continuations (see Common Patterns below)
2. Long running task must be delegated to background threads, never block a subscribers callback or other subscribers will not receive events in a timely manner

## Common Patterns

Subcribe to an event, do something in the background, then modify state afterwards with the result

```kotlin
//use subscribeWithPromise<T1, T2> where T1 is the event to subscribe and T2 is the expected result  
subscribeWithPromise<EventDownloadSomething, String> { event, state, promise ->
    //download something in background, it is not safe to modify state inside the coroutine
    state.download.scope.launch {
        val result = downloadSomething(event.url)
        promise.resolve(result) //this will trigger the 'then' continuation below
    }
}.then { result, state, promise ->
    //this will run on the same single thread for all subscriber callbacks
    state.download.result = result  //so it is safe to modify state here  
    //you can end here or do something more in background then call promise.resolve again to continue modifying state
}.then { result, state, promise ->
    //...continue modifying state here
}
```

Publish an event, wait for results, then modify state afterwards

```kotlin
//create an event that extends EventPromise<T> where T is the expected result
class EventPromiseLogin(val username: String, val password: String) :  EventPromise<Boolean>()

//in the subscriber, resolve the event when results are available
subscribe<EventLogin> { event, state ->
    //do something in the backround
    GlobalScope.launch {
        val isSuccess = login(event.username, event.password)
        event.resolve(isSuccess) //trigger the event's 'then' continuation
    }
}

//publish the event, modify state once result is available
publish(EventPromiseLogin()).then { isSuccess, state, promise ->
    if(isSuccess) {
        state.app.isLoggedIn = true
        publish(EventShowMainMenu())
    } else {
        state.app.isLoggedIn = false
        publish(EventShowWrongUserOrPassword())
    }
}

//publish the event from an activity, modify UI once result is available
publish(EventPromiseLogin()).thenUpdateUI(viewLifecycleOwner) { isSuccess ->
	//this callback occurs on the UI thread so state is not given and cannot be modified here
	//but you can update UI elements safely
	textViewStatus.text = if(isSuccess) "Logged In" else "Failed"
}
```

Subscribe to a related group of events, unsubscribe when no longer needed

```kotlin
//This object logs messages to logcat
object LogCatLogger {
    //subscribes to events and put them in the "LOG_EVENTS" group
    fun load() {
        subscribe<EventLogInfo>("LOG_EVENTS") { event, state -> Log.i(TAG, event.message) }
        subscribe<EventLogWarning>("LOG_EVENTS") { event, state -> Log.w(TAG, event.message) }
    }
    
    //unsubscribe all events in the "LOG_EVENTS" group
    fun unload() {
        unsubscribe("LOG_EVENTS")
    }
}

//This object logs message to a file
object FileLogger {
    //subscribes to events and put them in the "LOG_EVENTS" group
    fun load() {
        subscribe<EventLogInfo>("LOG_EVENTS") { event, state -> logInfoToFile(TAG, event.message) }
        subscribe<EventLogWarning>("LOG_EVENTS") { event, state -> logWarningToFile(TAG, event.message) }
    }
    
    //unsubscribe all events in the "LOG_EVENTS" group
    fun unload() {
        unsubscribe("LOG_EVENTS")
    }
}

//to switch to file logging from logcat logging simply load/unload the modules
LogCatLogger.unload()  //unsubscribes the logcat logger to log events
FileLogger.load()  //subscribes the file logger to log events
```

Subscribe and update the UI based on an event, unsubscribe when Activity/Fragment lifecycle state is Lifecycle.state.DESTROYED

```kotlin
//use subscribeUI passing in the Activity or Fragment viewLifecycleOwner, when the activity or fragment dies the event will be automatically unsubscribed
subscribeUI<EventItemUpdated>(viewLifecycleOwner) { event ->
    //this callback occurs on the UI thread so state is not given and cannot be modified here
	//but you can update UI elements safely
    recyclerViewResults?.adapter?.let { (it as MyAdapter).updateItem(event.index) }
}
```