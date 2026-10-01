package com.nsheaps.risetopower.core

// Trigonometry for the simulation. java.lang.Math may use platform intrinsics that differ in the
// last bit between devices; StrictMath gives identical results everywhere, which networked games
// need so that every device computes exactly the same simulation.

internal fun sin(x: Float): Float = StrictMath.sin(x.toDouble()).toFloat()
internal fun cos(x: Float): Float = StrictMath.cos(x.toDouble()).toFloat()
internal fun atan2(y: Float, x: Float): Float = StrictMath.atan2(y.toDouble(), x.toDouble()).toFloat()
