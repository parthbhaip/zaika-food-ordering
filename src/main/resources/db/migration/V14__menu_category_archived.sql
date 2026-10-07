-- Owner menu management: a category whose dishes were all ordered at some point cannot be deleted (archived dishes
-- still point at it), so deleting it archives it instead. Archived categories are hidden everywhere.
ALTER TABLE menu_categories ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
