/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 2/20/25.
 */


package llmtest.torpa2


import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import java.io.{FileInputStream, FileOutputStream, ObjectInputStream, ObjectOutputStream}
import java.nio.ByteBuffer
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*
import scala.util.{Random, Using}


trait ContextStorage[C] {
  def get(key: String): IO[Option[C]]
  def set(key: String, context: C): IO[Unit]
}


object ContextStorage {
  private def deleteAll(p: Path): Unit = {
    if Files.exists(p) then {
      if Files.isDirectory(p) then
        Files.list(p).iterator.asScala.foreach(deleteAll)
      Files.delete(p)
    }
  }

  private def kEnc(an: Array[Byte])(s: String): String = {
    var ni = an.length
    s.getBytes(StandardCharsets.UTF_8).map(b => {
      ni = if ni < 1 then an.length - 1 else ni - 1
      val be: Byte = (b ^ an(ni)).toByte
      f"$be%02x"
    }).mkString
  }

  def inMemory[C]: IO[ContextStorage[C]] = {
    Ref[IO].of(Map.empty[String, C]).map(ref =>
      new ContextStorage[C] {
        override def get(key: String): IO[Option[C]] = ref.get.map(_.get(key))

        override def set(key: String, context: C): IO[Unit] = ref.modify(m => m.updated(key, context) -> ())
      })
  }

  def inFiles[C <: Serializable]: Resource[IO, ContextStorage[C]] = {
    val pdp = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    val pfp = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    val rdp: Resource[IO, Path] = {
      val aquire = IO.blocking {
        Files.createTempDirectory("torpa2", pdp)
      }

      def release(p: Path): IO[Unit] = IO.blocking {
        deleteAll(p)
      }

      Resource.make(aquire)(release)
    }
    rdp.map(dirPath => {
      val seed = new Random(System.currentTimeMillis).nextInt(Int.MaxValue)
      val an = ByteBuffer.allocate(4).putInt(seed).array
      new ContextStorage[C] {
        override def get(key: String): IO[Option[C]] = IO.blocking {
          val fp = dirPath.resolve(kEnc(an)(key))
          if Files.exists(fp) then {
            Some(Using(new ObjectInputStream(new FileInputStream(fp.toFile)))(ois => ois.readObject.asInstanceOf[C]).get)
          } else None
        }

        override def set(key: String, context: C): IO[Unit] = IO.blocking {
          val fp = dirPath.resolve(kEnc(an)(key))
          if Files.exists(fp) then Files.delete(fp)
          Files.createFile(fp, pfp)
          Using(new ObjectOutputStream(new FileOutputStream(fp.toFile)))(oos => oos.writeObject(context)).get
        }
      }
    })
  }
}