package com.mesosphere.cosmos.util

import shapeless._

class RoundTrip[A] private(
  private val withResource: (A => Unit) => Unit
) {
  def flatMap[B](transform: A => RoundTrip[B]): RoundTrip[B] = {
    def withResourceB(bInner: B => Unit): Unit = {
      withResource { a: A =>
        transform(a).withResource { b: B =>
          bInner(b)
        }
      }
    }

    new RoundTrip[B](withResourceB)
  }

  def runWith[B](transform: A => B): B = {
    map(transform).run()
  }

  def run(): A = {
    var innerA: Option[A] = None
    withResource { a: A =>
      innerA = Some(a)
    }
    innerA.get
  }

  def map[B](transform: A => B): RoundTrip[B] = {
    def withResourceB(bInner: B => Unit): Unit = {
      withResource { a: A =>
        bInner(transform(a))
      }
    }

    new RoundTrip[B](withResourceB)
  }

  def hList(): RoundTrip[A :: HNil] = {
    map(_ :: HNil)
  }
}

object RoundTrip {
  def sequence[A](roundTrips: List[RoundTrip[A]]): RoundTrip[List[A]] = {
    roundTrips.foldRight(RoundTrip.lift(List(): List[A])) { (aRt, listRt) =>
      aRt.flatMap { a =>
        listRt.map { list =>
          a :: list
        }
      }
    }
  }

  def lift[A](a: => A): RoundTrip[A] = {
    RoundTrip(a)((_: A) => ())
  }

  def apply[A](
    forward: => A)(
    backwards: A => Any
  ): RoundTrip[A] = {
    def withResource(aInner: A => Unit): Unit = {
      val a = forward
      try {
        aInner(a)
      } finally {
        backwards(a)
        ()
      }
    }

    new RoundTrip[A](withResource)
  }

  // scalastyle:off method.name
  implicit class RoundTripHListOps[B <: HList](self: => RoundTrip[B]) {
    def &:[A](other: RoundTrip[A]): RoundTrip[A :: B] = {
      other.flatMap { a =>
        self.map(a :: _)
      }
    }
  }

  implicit class RoundTripOps[B](self: => RoundTrip[B]) {
    def &:[A](other: RoundTrip[A]): RoundTrip[A :: B :: HNil] = {
      other.flatMap { a =>
        self.map(b => a :: b :: HNil)
      }
    }
  }

  // scalastyle:on method.name
}
