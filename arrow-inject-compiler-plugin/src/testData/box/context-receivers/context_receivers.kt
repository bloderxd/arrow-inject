package foo.bar

import arrow.inject.annotations.context
import arrow.inject.annotations.Provider

@Provider class X
@Provider class Persistence2
@Provider class Y
@Provider class Z
@Provider class W

context(Persistence2, X, Y, Z, W)
class Repo(val x: Int)

fun f(): Int {
  println("test")
  context<Persistence2, X, Y, Z, W>()
  return Repo(0).x
}

fun f4(): Int {
  println("test")
  return with(Persistence2()) {
    with(X()) {
      with(Y()) {
        with(Z()) {
          with(W()) {
            Repo(0).x
          }
        }
      }
    }
  }
}

fun box(): String {
  val result = f()
  return if (result == 0) {
    "OK"
  } else {
    "Fail: $result"
  }
}
