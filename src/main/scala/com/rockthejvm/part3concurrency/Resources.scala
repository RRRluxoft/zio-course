package com.rockthejvm.part3concurrency

import com.rockthejvm.utils.*
import zio.*

import java.io.File
import java.util.Scanner

object Resources extends ZIOAppDefault {

  // finalizers
  def unsafeMethod(): Int = throw new RuntimeException("Not an int here for you!")
  val anAttempt = ZIO.attempt(unsafeMethod())

  // finalizers
  val attemptWithFinalizer = anAttempt.ensuring(ZIO.succeed("finalizer here").debugThread)
//  .attempt(unsafeMethod())
//    .ensuring(ZIO.succeed("finalizer here").debugThread) // happens BEFORE an error !!!

  // multiple finalizers
  val attemptWith2Finalizers = attemptWithFinalizer
    .ensuring(ZIO.succeed("another finalizer here >>>").debugThread) // in order: 1st ensuring, 2nd ensuring, error
  // .onInterrupt, .onError, .onDone, .onExit

  // resource lifecycle
  class Connection(val url: String) {
    def open() = ZIO.succeed(s"opening connection to $url ...").debugThread
    def close() = ZIO.succeed(s"closing connection $url").debugThread
  }

  object Connection {
    def create(url: String) = ZIO.succeed[Connection](new Connection(url))
  }

  val fetchUrl = for {
    conn <- Connection.create("rockthejvm.com")
    fib  <- (conn.open() *> ZIO.sleep(300.seconds)).fork
    _    <- ZIO.sleep(1.second) *> ZIO.succeed("...interrupting...").debugThread *> fib.interrupt
    _    <- fib.join
  } yield () // resource leak

  val correctFetchUrl = for {
    conn <- Connection.create("rockthejvm.com")
    fib  <- (conn.open() *> ZIO.sleep(300.seconds))
              .ensuring(conn.close() *> ZIO.succeed(s"successfully closed ${conn.url}"))
              .fork
    _    <- ZIO.sleep(1.second) *> ZIO.succeed("...interrupting...").debugThread *> fib.interrupt
    _    <- fib.join
  } yield ZIO.unit // resource leak

  // tedious

  /*
   acquireRelease
    - acquiring cannot be interrupted
    - all finalizers are guaranteed to run
   */
  val cleanConnection: ZIO[Any with Scope, Nothing, Connection] = ZIO.acquireRelease(
    /* 1.*/ acquire = Connection.create("rockthejvm.com")
  )(
    /* 2.*/ release = _.close()
  )

  val fetchWithResource = for {
    conn <- cleanConnection
    fib  <- (conn.open() *> ZIO.sleep(300.seconds)).fork
    _    <- ZIO.sleep(1.second) *> ZIO.succeed("...interrupting...").debugThread *> fib.interrupt
    _    <- fib.join
  } yield ZIO.unit

  val fetchWithScopedResource = ZIO.scoped(fetchWithResource)

  // acquireReleaseWith
  val cleanConnection_v2: ZIO[Any, Nothing, Unit] = ZIO.acquireReleaseWith(
    acquire = Connection.create("rockthejvm.com")
  )(
    release = _.close()
  )(conn => conn.open() *> ZIO.sleep(300.seconds))

  val fetchWithResource_v2 = for {
    fib  <- cleanConnection_v2.fork
    _    <- ZIO.sleep(1.second) *> ZIO.succeed("...interrupting...").debugThread *> fib.interrupt
    _    <- fib.join
  } yield ZIO.unit

  /**
    * Exercises
    * 1. Use the acquireRelease to open a file, print all lines, (one every 100 millis), then close the file
    */

    def openFileScanner(path: String): UIO[Scanner] = ZIO.succeed(new Scanner(new File(path)))

  def readLineByLine(scanner: Scanner): UIO[Unit] =
    if scanner.hasNextLine
    then ZIO.succeed(scanner.nextLine()).debugThread
      *> ZIO.sleep(100.millis)
      *> readLineByLine(scanner)
    else ZIO.unit

  def acquireOpenFile(path: String): UIO[Unit] =
    ZIO.succeed(s"opning file at $path").debugThread *>
      ZIO.acquireReleaseWith(
        acquire = openFileScanner(path)
      )(
        release = scanner => ZIO.succeed(scanner.close()) <* ZIO.succeed(s"closing file at $path").debugThread
      ) ( scanner =>
        readLineByLine(scanner)
      )

  val testInterruptFileDisplay = for {
    fib <- acquireOpenFile("src/main/scala/com/rockthejvm/part3concurrency/Resources.scala")
      .fork
    _ <- ZIO.sleep(2.seconds) *> fib.interrupt
  } yield ()

  // acquireRelease vs acquireReleaseWith
  def connFromConfig(path: String): UIO[Unit] =
    ZIO.acquireReleaseWith(openFileScanner(path))(scanner =>
      ZIO.succeed(scanner.close()) <* ZIO.succeed(s"closing file").debugThread) { scanner =>
        ZIO.acquireReleaseWith(Connection.create(scanner.nextLine()))(_.close()) { conn =>
          conn.open() *> ZIO.never
      }
    }

  // nested resource
  def connFromConfig_v2(path: String): UIO[Unit] = ZIO.scoped {
    for {
      scanner <- ZIO.acquireRelease(openFileScanner(path))(scanner =>
        ZIO.succeed(scanner.close()) <* ZIO.succeed(s"closing file").debugThread)
      conn <- ZIO.acquireRelease(Connection.create(scanner.nextLine()))(_.close())
      _ <- conn.open() *> ZIO.never
    } yield ()
  }

  def run = connFromConfig_v2("src/main/resources/connection.conf")
}
