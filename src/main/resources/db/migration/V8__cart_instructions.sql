-- A note for the restaurant ("less spicy, no onion"), kept with the saved cart until the order is placed.
ALTER TABLE carts ADD COLUMN instructions VARCHAR(250);
