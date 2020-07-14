package com.mycode.kotlinsingle

import androidx.lifecycle.LifecycleOwner

class EventPromiseResolve (val tag: String, val lifecycleOwner: LifecycleOwner?, val handler : ()->Unit) : Event()