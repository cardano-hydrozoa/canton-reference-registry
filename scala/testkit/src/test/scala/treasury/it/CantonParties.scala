package treasury.it

import io.grpc.netty.NettyChannelBuilder

import com.daml.ledger.api.v2.admin.PartyManagementServiceGrpc
import com.daml.ledger.api.v2.admin.PartyManagementServiceOuterClass.AllocatePartyRequest

import treasury.PartyId

/** Party allocation over the Ledger API v2 admin `PartyManagementService`. The rxjava
  * `DamlLedgerClient` doesn't expose party management, so we use the raw gRPC stub (bundled in
  * bindings-java) on a plaintext channel. Returns the real, namespaced party ids
  * (`hint::namespace-hash`) keyed by hint.
  */
object CantonParties:

    def allocate(host: String, port: Int, hints: List[String]): Map[String, PartyId] =
        val channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
        try
            val stub = PartyManagementServiceGrpc.newBlockingStub(channel)
            hints.map { hint =>
                val resp = stub.allocateParty(
                  AllocatePartyRequest.newBuilder().setPartyIdHint(hint).build()
                )
                hint -> PartyId(resp.getPartyDetails.getParty)
            }.toMap
        finally
            val _ = channel.shutdownNow()
