package com.zaika.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CuisineRepository extends JpaRepository<Cuisine, Long> {

	Optional<Cuisine> findBySlug(String slug);

	List<Cuisine> findAllByOrderByNameAsc();

}
