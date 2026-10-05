-- /restaurants filters on rating_count (top rated needs at least one rating) and sorts by it after rating_average
-- (relevance and rating sorts). Covers the status + rating filter and both sort columns.
CREATE INDEX idx_restaurants_status_rating_count ON restaurants (status, rating_average, rating_count);
