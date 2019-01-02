package net.andimiller

import java.net.{ConnectException, Socket}

import cats._
import cats.implicits._
import cats.syntax._
import cats.data._
import cats.effect._
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.LogsParam
import com.spotify.docker.client.messages.ContainerConfig.NetworkingConfig
import com.spotify.docker.client.messages._
import fs2._

import scala.concurrent.duration._
import scala.collection.JavaConverters._

package object whales {

  case class DockerImage(
      image: String,
      version: String,
      name: Option[String] = None,
      network: Option[String] = None,
      command: Option[String] = None,
      ports: List[Int] = List.empty,
      env: Map[String, String] = Map.empty,
      volumes: Map[String, String] = Map.empty
  )

  case class ExitedContainer(code: Long, logs: String)

  case class DockerContainer(creation: DockerImage, container: ContainerInfo) {
    def waitForPort[F[_]: Sync: Timer](port: Int, backoffs: Int = 5, delay: FiniteDuration = 1 second): Resource[F, Unit] =
      Resource.liftF(
        Docker
          .waitTcp[F](container.networkSettings().ipAddress(), port, backoffs = backoffs, delay = delay)
          .attemptT
          .recover {
            case e: ConnectException =>
              throw new ConnectException(s"Unable to connect to ${creation.image}:${creation.version} (${creation.name}) on port $port: ${e.getMessage}")
          }
          .value
          .rethrow
      )

    def waitForExit[F[_]: Sync: Timer](docker: DockerClient[F], backoffs: Int = 5, delay: FiniteDuration = 1 second): Resource[F, ExitedContainer] =
      Resource.liftF(
        Docker.waitExit[F](docker.docker, container.id(), backoffs, delay)
      )

    def ipAddress: String = container.networkSettings().ipAddress()
  }

  object Docker {
    private[whales] def client[F[_]](implicit F: Effect[F]): Resource[F, DefaultDockerClient] =
      Resource.make(
        F.delay {
          DefaultDockerClient.fromEnv().build()
        }
      ) { c =>
        F.delay {
          c.close()
        }
      }


    private[whales] def waitExit[F[_]: Sync: Timer](docker: DefaultDockerClient, id: String, backoffs: Int = 5, delay: FiniteDuration = 1 second): F[ExitedContainer] =
      Stream.retry(Sync[F].delay {
        val state = docker.inspectContainer(id).state()
        assert(state.running() == false, s"Container $id still running")
        ExitedContainer(state.exitCode(), docker.logs(id, LogsParam.stdout(), LogsParam.stderr()).readFully())
      }, delay = delay, nextDelay = _ * 2, maxAttempts = backoffs)
      .take(1)
      .compile
      .lastOrError

    private[whales] def waitTcp[F[_]: Sync: Timer](host: String, port: Int, backoffs: Int = 5, delay: FiniteDuration = 1 second): F[Unit] =
      Stream
        .retry(Sync[F].delay {
          new Socket(host, port)
        }, delay = delay, nextDelay = _ * 2, maxAttempts = backoffs)
        .compile
        .drain

    def apply[F[_]: Effect]: Resource[F, DockerClient[F]] = client[F].map(c => DockerClient[F](c))
  }

  case class DockerClient[F[_]](docker: DefaultDockerClient) {

    def apply(image: String,
              version: String,
              name: Option[String] = None,
              network: Option[String] = None,
              command: Option[String] = None,
              ports: List[Int] = List.empty,
              env: Map[String, String] = Map.empty,
              volumes: Map[String, String] = Map.empty)(implicit F: Effect[F]): Resource[F, DockerContainer] =
      apply(DockerImage(image, version, name, network, command, ports, env, volumes))

    def network(name: String)(implicit F: Effect[F]): Resource[F, NetworkCreation] =
      Resource.make(
        F.delay {
          val network = NetworkConfig.builder().name(name).build()
          docker.createNetwork(network)
        }
      ) { n =>
        F.delay {
          docker.removeNetwork(n.id())
        }
      }

    def apply(image: DockerImage)(implicit F: Effect[F]): Resource[F, DockerContainer] =
      Resource.make(
        F.delay {
          val container = ContainerConfig
            .builder()
            .hostConfig(
              HostConfig.builder().appendBinds(image.volumes.map { case (k, v) => s"$k:$v" }.asJava).build()
            )
            .image(image.image + ":" + image.version)
            .exposedPorts(image.ports.map(_.toString): _*)
            .env(image.env.map { case (k, v) => s"$k=$v" }.toList.asJava)
          val withCommand = image.command.foldLeft(container)((c, s) => c.cmd(s))
          docker.pull(image.image+ ":" + image.version)
          val creation = image.name match {
            case Some(name) => docker.createContainer(withCommand.build(), name)
            case None       => docker.createContainer(withCommand.build())
          }
          image.network.foreach { network =>
            docker.connectToNetwork(creation.id(), network)
          }
          docker.startContainer(creation.id())
          DockerContainer(image, docker.inspectContainer(creation.id()))
        }
      ) {
        case DockerContainer(_, c) =>
          F.delay {
            val container = docker.inspectContainer(c.id())
            if (container.state().running()) {
              docker.killContainer(c.id())
            }
            docker.removeContainer(c.id())
          }
      }
  }
}