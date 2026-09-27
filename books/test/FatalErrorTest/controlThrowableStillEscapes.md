# `return` inside a closure is still not caught


The fix widens the case catch to `NonFatal || VirtualMachineError` rather
than to `Throwable`, and that distinction is load-bearing: Scala compiles
a `return` inside a closure into a ControlThrowable, which is neither. If
the catch swallowed it, `return` in a test body would silently stop
meaning what it says.

return-from-closure produced: 42
[OK] a non-local return still returns its value (42): PASS
