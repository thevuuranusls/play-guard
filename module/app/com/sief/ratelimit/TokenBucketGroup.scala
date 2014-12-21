package com.sief.ratelimit

import java.util.concurrent.TimeUnit

import scala.concurrent.{ExecutionContext, Future}

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout

/**
 * Token Bucket implementation as described here http://en.wikipedia.org/wiki/Token_bucket
 */


/**
 * For mocking the current time.
 */
trait Clock {
  def now: Long
}

/**
 * Actor message for consuming tokens
 * @param key bucket key
 * @param count requested amount or tokens
 */
private case class TokenRequest(key: Any, count: Int)

/**
 * Actor which synchronizes the bucket token requests
 * @param size bucket size
 * @param rate refill rate in tokens per second
 * @param clock for mocking the current time.
 */
private class TokenBucketGroup(size: Int, rate: Float, clock: Clock) extends Actor with ActorLogging {

  private val intervalMillis = (1000 / rate).toLong

  private val ratePerMilli = rate / 1000

  private var lastRefill = clock.now

  private var buckets = Map.empty[Any, Long]


  /**
   * Actor´s inbox
   * @return
   */
  override def receive = LoggingReceive {

    /**
     * First refills all buckets at the given rate, then tries to consume the required amount.
     */
    case TokenRequest(key, required) =>
      refillAll()
      val newLevel = (buckets.getOrElse(key, size.toLong) - required).toInt
      if (newLevel >= 0) {
        buckets = buckets + (key -> newLevel)
      }
      sender ! newLevel
  }

  /**
   * Refills all buckets at the given rate. Full buckets are removed.
   */
  private def refillAll() {
    val now = clock.now
    val diff = now - lastRefill
    val tokensToAdd = (diff * ratePerMilli).toLong
    if (tokensToAdd > 0) {
      buckets = buckets.mapValues(_ + tokensToAdd).filterNot(_._2 >= size)
      lastRefill = now - diff % intervalMillis
    }
  }
}

object TokenBucketGroup {

  /**
   * Creates the actor and bucket group.
   * @param system actor system
   * @param size bucket size. Has to be in the range 0 to 1000.
   * @param rate refill rate in tokens per second. Has to be in the range 0 to 1000.
   * @param clock for mocking the current time.
   * @param context akka execution context
   * @return actorRef, needed to call consume later.
   */
  def create(system: ActorSystem, size: Int, rate: Float, clock: Clock = new Clock {
    override def now: Long = System.currentTimeMillis
  })(implicit context: ExecutionContext): ActorRef = {
    require(size > 0)
    require(size <= 1000)
    require(rate > 0)
    require(rate <= 1000)
    system.actorOf(Props(new TokenBucketGroup(size, rate, clock)))
  }

  /**
   * Try to consume count tokens. If the returned value is negative, no tokens are consumed.
   * @param actor actorRef returned by create
   * @param key bucket key
   * @param count count of tokens to consume, pass 0 to query just the current level without consuming a token. No negative values allowed.
   * @param timeout akka timeout
   * @return (remainingTokens - count), if negative no tokens are consumed.
   */
  def consume(actor: ActorRef, key: Any, count: Int)(implicit timeout: Timeout = Timeout(100, TimeUnit.MILLISECONDS)): Future[Int] = {
    require(count >= 0)
    (actor ? TokenRequest(key, count)).mapTo[Int]
  }
}