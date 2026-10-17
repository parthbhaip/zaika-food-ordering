package com.zaika.user;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

	/**
	 * Case-insensitive lookup: emails are stored lower-case, so the argument is normalized the same way and the
	 * unique index on {@code users.email} still serves the query (no {@code UPPER(email)} scan).
	 */
	default Optional<User> findByEmailIgnoreCase(String email) {
		return email == null ? Optional.empty() : findByEmail(User.normalizeEmail(email));
	}

	/** Same, as an existence check (duplicate email on sign-up). */
	default boolean existsByEmailIgnoreCase(String email) {
		return email != null && existsByEmail(User.normalizeEmail(email));
	}

	/** Exact match on the stored (already lower-case) value. Prefer {@link #findByEmailIgnoreCase}. */
	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/** Is there at least one account with this role (e.g. an admin, checked on startup)? Uses idx_users_role. */
	boolean existsByRole(Role role);

	/** Enabled accounts with this role (idx_users_role). */
	long countByRoleAndEnabledTrue(Role role);

	/** Exact stored email with a row lock, so concurrent failed sign-ins are all counted. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.email = :email")
	Optional<User> findForUpdateByEmail(String email);

	/** Loads the user with a row lock, to serialize per-user limits (e.g. the 10-address cap). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findForUpdate(long id);

	/** Just the role, without loading the account (so a later locking read sees fresh state). */
	@Query("select u.role from User u where u.id = :id")
	Optional<Role> findRoleById(long id);

	/**
	 * Every account with this role, row-locked in id order: disabling an admin locks them all, so two admins
	 * switching each other off at once cannot leave nobody able to sign in.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.role = :role order by u.id")
	List<User> findAllForUpdateByRole(Role role);

	/**
	 * The admin user list, newest sign-ups first: accounts with {@code role} (any when null) whose name or email is
	 * LIKE {@code pattern} (lower-cased, {@code !} escapes; any when null). Uses {@code idx_users_role_created} /
	 * {@code idx_users_created}.
	 */
	@Query(value = """
			select u from User u
			where (:role is null or u.role = :role)
				and (:pattern is null or lower(u.name) like :pattern escape '!' or u.email like :pattern escape '!')
			order by u.createdAt desc, u.id desc""", countQuery = """
			select count(u) from User u
			where (:role is null or u.role = :role)
				and (:pattern is null or lower(u.name) like :pattern escape '!' or u.email like :pattern escape '!')""")
	Page<User> findForAdmin(Role role, String pattern, Pageable pageable);

	/** How many accounts each role has (roles with none are missing). */
	record RoleCount(Role role, Long users) {
	}

	@Query("select new com.zaika.user.UserRepository$RoleCount(u.role, count(u)) from User u group by u.role")
	List<RoleCount> countPerRole();

}
