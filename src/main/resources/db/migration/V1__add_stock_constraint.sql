-- Migration: Add database constraint to prevent negative stock
-- This ensures that stock_quantity can never go below 0, even under extreme concurrent load
-- The constraint is enforced at the database level, providing the strongest protection

-- First, fix any existing negative stock values (set to 0)
UPDATE inventory 
SET stock_quantity = 0 
WHERE stock_quantity < 0;

-- Add check constraint to prevent negative stock
ALTER TABLE inventory 
ADD CONSTRAINT check_stock_non_negative 
CHECK (stock_quantity >= 0);

-- Verify the constraint was added
-- You can check with: SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'check_stock_non_negative';
