-- Loans to members of the tenant are linked to their account, so members can see and return their own loans. Loans
-- to someone without an account (e.g. another Stamm) only have a name. For linked loans, `borrower` keeps the
-- member's name, which is still shown after the account was deleted.
ALTER TABLE loans ADD COLUMN borrower_id UUID REFERENCES users ON DELETE SET NULL;

CREATE INDEX idx_loans_borrower_id ON loans (borrower_id);
