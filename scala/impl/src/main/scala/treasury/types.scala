package treasury

// A ledger party. Opaque over `String` (Daml's codegen models parties as bare strings) so a party id
// can't be silently swapped with another string; convert with `PartyId(s)` / `p.value` at the codegen
// and Ledger-API boundaries.
opaque type PartyId = String
object PartyId:
    def apply(s: String): PartyId = s
    extension (p: PartyId) def value: String = p
