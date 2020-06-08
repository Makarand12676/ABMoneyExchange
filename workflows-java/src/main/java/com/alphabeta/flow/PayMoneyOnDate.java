package com.alphabeta.flow;

import co.paralleluniverse.fibers.Suspendable;
import com.alphabeta.contract.IOUContract;
import com.alphabeta.state.IOUState;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;

import static net.corda.core.contracts.ContractsDSL.requireThat;

/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the IOU encapsulated
 * within an [IOUState].
 *
 * In our simple alphabeta, the [Acceptor] always accepts a valid IOU.
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
public class PayMoneyOnDate {
    @InitiatingFlow
    @StartableByRPC
    public static class Initiator extends FlowLogic<SignedTransaction> {

        private final int iouValue;
        private final Party otherParty;
        private  Date executionDate;
        private String reciverPh;
        private String smsMsg = "Money transferred !!";
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");

        private final Step VALIDATING_DATE = new Step("Checking Paydate is in past...");
        private final Step GENERATING_TRANSACTION = new Step("Generating transaction based on new IOU.");
        private final Step VERIFYING_TRANSACTION = new Step("Verifying contract constraints.");
        private final Step SIGNING_TRANSACTION = new Step("Signing transaction with our private key.");
        private final Step GATHERING_SIGS = new Step("Gathering the counterparty's signature.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        private final Step FINALISING_TRANSACTION = new Step("Obtaining notary signature and recording transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };
        private final Step SENDING_SMS = new Step("Sending SMS...");

        // The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
        // checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call()
        // function.
        private final ProgressTracker progressTracker = new ProgressTracker(
                VALIDATING_DATE,
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION,
                SENDING_SMS
        );

        public Initiator(int iouValue, Party otherParty, String date, String phone) {
            this.iouValue = iouValue;
            this.otherParty = otherParty;
            this.reciverPh = phone;
            try {
                this.executionDate = df.parse(date);
            } catch (ParseException e) {
                e.printStackTrace();
            }

        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            // Stage 0 - flow level conditions
            progressTracker.setCurrentStep(VALIDATING_DATE);


            try {
                if(executionDate.before(df.parse(LocalDate.now().toString()))){
                    throw new FlowException("Paydate is in future !");
                }
            } catch (ParseException e) {
                e.printStackTrace();
            }

            // Obtain a reference to the notary we want to use.
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

            // Stage 1.
            progressTracker.setCurrentStep(GENERATING_TRANSACTION);
            // Generate an unsigned transaction.
            Party me = getOurIdentity();
            IOUState iouState = new IOUState(iouValue, me, otherParty, new UniqueIdentifier());
            final Command<IOUContract.Commands.Create> txCommand = new Command<>(
                    new IOUContract.Commands.Create(),
                    ImmutableList.of(iouState.getLender().getOwningKey(), iouState.getBorrower().getOwningKey()));
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addOutputState(iouState, IOUContract.ID)
                    .addCommand(txCommand);

            // Stage 2.
            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
            // Verify that the transaction is valid.
            txBuilder.verify(getServiceHub());

            // Stage 3.
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            // Sign the transaction.
            final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

            // Stage 4.
            progressTracker.setCurrentStep(GATHERING_SIGS);
            // Send the state to the counterparty, and receive it back with their signature.
            FlowSession otherPartySession = initiateFlow(otherParty);
            final SignedTransaction fullySignedTx = subFlow(
                    new CollectSignaturesFlow(partSignedTx, ImmutableSet.of(otherPartySession), CollectSignaturesFlow.Companion.tracker()));

            // Stage 5.
            progressTracker.setCurrentStep(SENDING_SMS);
            SendSMS.sendMessage(this.reciverPh,this.smsMsg);


            // Stage 6.
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(new FinalityFlow(fullySignedTx, ImmutableSet.of(otherPartySession)));
        }
    }

    @InitiatedBy(Initiator.class)
    public static class Acceptor extends FlowLogic<SignedTransaction> {

        private final FlowSession otherPartySession;

        public Acceptor(FlowSession otherPartySession) {
            this.otherPartySession = otherPartySession;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            class SignTxFlow extends SignTransactionFlow {
                private SignTxFlow(FlowSession otherPartyFlow, ProgressTracker progressTracker) {
                    super(otherPartyFlow, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) {
                    requireThat(require -> {
                        ContractState output = stx.getTx().getOutputs().get(0).getData();
                        require.using("This must be an IOU transaction.", output instanceof IOUState);
                        IOUState iou = (IOUState) output;
                        require.using("I won't accept IOUs with a value over 100.", iou.getValue() <= 100);
                        return null;
                    });
                }
            }
            final SignTxFlow signTxFlow = new SignTxFlow(otherPartySession, SignTransactionFlow.Companion.tracker());
            final SecureHash txId = subFlow(signTxFlow).getId();

            return subFlow(new ReceiveFinalityFlow(otherPartySession, txId));
        }
    }
}
