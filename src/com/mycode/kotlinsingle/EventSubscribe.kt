package com.mycode.kotlinsingle

import androidx.lifecycle.LifecycleOwner
import kotlin.reflect.KClass

class EventSubscribe (val eventClass : KClass<*>,
                      val subscriberGroupName : String?,
                      val lifecycleOwner: LifecycleOwner?,
                      val promiseType : KClass<*>,
                      val callback : Any,
                      val promise: Promise<*>) : Event()