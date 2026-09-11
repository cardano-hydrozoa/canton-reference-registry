package treasury

// A ledger party. Daml's codegen models parties as bare `String`s (e.g. Account.owner :
// Optional[String]); we keep the same representation rather than wrapping, so values flow into the
// generated Java constructors without conversion.
type PartyId = String
