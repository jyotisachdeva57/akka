/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehavior
import akka.persistence.typed.scaladsl.ReplyEffect

/**
 * Bank account example illustrating:
 * - different state classes representing the lifecycle of the account
 * - event handlers in the state classes
 * - command handlers outside the state classes, pattern matching of commands in one place that
 *   is delegating to methods
 * - replies of various types, using ExpectingReply and withEnforcedReplies
 */
object AccountExampleWithEventHandlersInState {

  object AccountEntity {
    // Command
    sealed trait AccountCommand[Reply] extends ExpectingReply[Reply]
    final case class CreateAccount()(override val replyTo: ActorRef[OperationResult])
      extends AccountCommand[OperationResult]
    final case class Deposit(amount: BigDecimal)(override val replyTo: ActorRef[OperationResult])
      extends AccountCommand[OperationResult]
    final case class Withdraw(amount: BigDecimal)(override val replyTo: ActorRef[OperationResult])
      extends AccountCommand[OperationResult]
    final case class GetBalance()(override val replyTo: ActorRef[CurrentBalance])
      extends AccountCommand[CurrentBalance]
    final case class CloseAccount()(override val replyTo: ActorRef[OperationResult])
      extends AccountCommand[OperationResult]

    // Reply
    sealed trait AccountCommandReply
    sealed trait OperationResult extends AccountCommandReply
    case object Confirmed extends OperationResult
    final case class Rejected(reason: String) extends OperationResult
    final case class CurrentBalance(balance: BigDecimal) extends AccountCommandReply

    // Event
    sealed trait AccountEvent
    case object AccountCreated extends AccountEvent
    case class Deposited(amount: BigDecimal) extends AccountEvent
    case class Withdrawn(amount: BigDecimal) extends AccountEvent
    case object AccountClosed extends AccountEvent

    val Zero = BigDecimal(0)

    // State
    sealed trait Account {
      def applyEvent(event: AccountEvent): Account
    }
    case object EmptyAccount extends Account {
      override def applyEvent(event: AccountEvent): Account = event match {
        case AccountCreated ⇒ OpenedAccount(Zero)
        case _              ⇒ throw new IllegalStateException(s"unexpected event [$event] in state [EmptyAccount]")
      }
    }
    case class OpenedAccount(balance: BigDecimal) extends Account {
      require(balance >= Zero, "Account balance can't be negative")

      override def applyEvent(event: AccountEvent): Account = event match {
        case Deposited(amount) ⇒ copy(balance = balance + amount)
        case Withdrawn(amount) ⇒ copy(balance = balance - amount)
        case AccountClosed     ⇒ ClosedAccount
        case _                 ⇒ throw new IllegalStateException(s"unexpected event [$event] in state [OpenedAccount]")
      }

      def canWithdraw(amount: BigDecimal): Boolean = {
        balance - amount >= Zero
      }

    }
    case object ClosedAccount extends Account {
      override def applyEvent(event: AccountEvent): Account =
        throw new IllegalStateException(s"unexpected event [$event] in state [ClosedAccount]")
    }

    // Note that after defining command, event and state classes you would probably start here when writing this.
    // When filling in the parameters of PersistentBehaviors.apply you can use IntelliJ alt+Enter > createValue
    // to generate the stub with types for the command and event handlers.

    def behavior(accountNumber: String): Behavior[AccountCommand[AccountCommandReply]] = {
      PersistentBehavior.withEnforcedReplies(
        PersistenceId(s"Account|$accountNumber"),
        EmptyAccount,
        commandHandler,
        eventHandler
      )
    }

    private val commandHandler: (Account, AccountCommand[_]) ⇒ ReplyEffect[AccountEvent, Account] = {
      (state, cmd) ⇒
        state match {
          case EmptyAccount ⇒ cmd match {
            case c: CreateAccount ⇒ createAccount(c)
            case _                ⇒ Effect.unhandled.thenNoReply(cmd)
          }

          case acc @ OpenedAccount(_) ⇒ cmd match {
            case c: Deposit       ⇒ deposit(c)
            case c: Withdraw      ⇒ withdraw(acc, c)
            case c: GetBalance    ⇒ getBalance(acc, c)
            case c: CloseAccount  ⇒ closeAccount(acc, c)
            case _: CreateAccount ⇒ Effect.unhandled.thenNoReply(cmd)
          }

          case ClosedAccount ⇒
            Effect.unhandled.thenNoReply(cmd)
        }
    }

    private val eventHandler: (Account, AccountEvent) ⇒ Account = {
      (state, event) ⇒ state.applyEvent(event)
    }

    private def createAccount(cmd: CreateAccount): ReplyEffect[AccountEvent, Account] = {
      Effect.persist(AccountCreated)
        .thenReply(cmd)(_ ⇒ Confirmed)
    }

    private def deposit(cmd: Deposit): ReplyEffect[AccountEvent, Account] = {
      Effect.persist(Deposited(cmd.amount))
        .thenReply(cmd)(_ ⇒ Confirmed)
    }

    private def withdraw(acc: OpenedAccount, cmd: Withdraw): ReplyEffect[AccountEvent, Account] = {
      if (acc.canWithdraw(cmd.amount)) {
        Effect.persist(Withdrawn(cmd.amount))
          .thenReply(cmd)(_ ⇒ Confirmed)

      } else {
        Effect.reply(cmd)(Rejected(s"Insufficient balance ${acc.balance} to be able to withdraw ${cmd.amount}"))
      }
    }

    private def getBalance(acc: OpenedAccount, cmd: GetBalance): ReplyEffect[AccountEvent, Account] = {
      Effect.reply(cmd)(CurrentBalance(acc.balance))
    }

    private def closeAccount(acc: OpenedAccount, cmd: CloseAccount): ReplyEffect[AccountEvent, Account] = {
      if (acc.balance == Zero)
        Effect.persist(AccountClosed)
          .thenReply(cmd)(_ ⇒ Confirmed)
      else
        Effect.reply(cmd)(Rejected("Can't close account with non-zero balance"))
    }

  }

}
