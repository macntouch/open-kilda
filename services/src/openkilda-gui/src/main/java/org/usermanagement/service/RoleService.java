package org.usermanagement.service;

import org.openkilda.constants.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.usermanagement.conversion.RoleConversionUtil;
import org.usermanagement.dao.entity.PermissionEntity;
import org.usermanagement.dao.entity.RoleEntity;
import org.usermanagement.dao.entity.UserEntity;
import org.usermanagement.dao.repository.PermissionRepository;
import org.usermanagement.dao.repository.RoleRepository;
import org.usermanagement.dao.repository.UserRepository;
import org.usermanagement.exception.RequestValidationException;
import org.usermanagement.model.Permission;
import org.usermanagement.model.Role;
import org.usermanagement.util.MessageUtils;
import org.usermanagement.util.ValidatorUtil;
import org.usermanagement.validator.RoleValidator;

@Service
@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
public class RoleService {

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private PermissionRepository permissionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MessageUtils messageUtil;

	@Autowired
	private RoleValidator roleValidator;

	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public Role createRole(final Role roleRequest) {
		roleValidator.validateRole(roleRequest);
		Set<PermissionEntity> permissionEntities = new HashSet<>();
		List<PermissionEntity> permissionEntityList = permissionRepository.findAll();
		for (Long permissionId : roleRequest.getPermissionId()) {
			PermissionEntity permissionEntity = permissionEntityList.parallelStream()
					.filter((entity) -> entity.getPermissionId().equals(permissionId)).findFirst().orElse(null);

			if (!ValidatorUtil.isNull(permissionEntity)) {
				permissionEntities.add(permissionEntity);
			} else {
				throw new RequestValidationException(messageUtil.getAttributeNotFound("permission"));
			}
		}

		RoleEntity roleEntity = RoleConversionUtil.toRoleEntity(roleRequest, permissionEntities);
		roleRepository.save(roleEntity);
		return RoleConversionUtil.toRole(roleEntity);
	}

	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public List<Role> getAllRole() {
		List<RoleEntity> roleEntityList = roleRepository.findAll();
		List<Role> roleList = RoleConversionUtil.toAllRoleResponse(roleEntityList);
		return roleList;
	}

	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public Role getRoleById(final Long roleId) {

		RoleEntity roleEntity = roleRepository.findByroleId(roleId);
		if (ValidatorUtil.isNull(roleEntity)) {
			throw new RequestValidationException(messageUtil.getAttributeInvalid("role_id", roleId + ""));
		}

		return RoleConversionUtil.toRole(roleEntity);
	}

	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public void deleteRoleById(Long roleId) {

		RoleEntity roleEntity = roleRepository.findByroleId(roleId);
		if (ValidatorUtil.isNull(roleEntity)) {
			throw new RequestValidationException(messageUtil.getAttributeInvalid("role_id", roleId + ""));
		}

		Set<UserEntity> userEntityList = userRepository.findByRoles_roleId(roleId);
		if (userEntityList.size() > 0) {
			String users = "";
			for (UserEntity userEntity : userEntityList) {
				users += !"".equals(users) ? "," + userEntity.getName() : userEntity.getName();
			}
			throw new RequestValidationException(
					messageUtil.getAttributeDeletionNotAllowed(roleEntity.getName(), users));
		}
		roleRepository.delete(roleEntity);
	}

	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public Permission getRolesByPermissionId(final Long permissionId) {

		PermissionEntity permissionEntity = permissionRepository.findByPermissionId(permissionId);

		Set<RoleEntity> roleEntityList = roleRepository.findByPermissions_permissionId(permissionId);

		return RoleConversionUtil.toPermissionByRole(roleEntityList, permissionEntity);
	}

	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public Role updateRole(Long roleId, final Role role) {

		roleValidator.validateUpdateRole(role, roleId);

		RoleEntity roleEntity = roleRepository.findByroleId(roleId);

		if (ValidatorUtil.isNull(roleEntity)) {
			throw new RequestValidationException(messageUtil.getAttributeInvalid("role_id", roleId + ""));
		}

		if (role.getPermissionId() != null) {
			roleEntity.getPermissions().clear();
			for (Long permissionId : role.getPermissionId()) {
				PermissionEntity permissionEntity = permissionRepository.findByPermissionId(permissionId);
				if (permissionEntity != null) {
					roleEntity.getPermissions().add(permissionEntity);
				}
			}
		}
		roleEntity = RoleConversionUtil.toUpateRoleEntity(role, roleEntity);
		roleRepository.save(roleEntity);

		return RoleConversionUtil.toRole(roleEntity);

	}

	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public Permission assignRoleByPermissionId(final Long permissionId, final Permission request) {
		PermissionEntity permissionEntity = permissionRepository.findByPermissionId(permissionId);
		permissionEntity.getRoles().clear();

		if (request.getRoles() != null) {
			for (Role role : request.getRoles()) {
				RoleEntity roleEntity = roleRepository.findByroleId(role.getRoleId());
				permissionEntity.getRoles().add(roleEntity);
			}
		}
		permissionRepository.save(permissionEntity);

		return RoleConversionUtil.toPermissionByRole(permissionEntity.getRoles(), permissionEntity);
	}
	
	@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
	public List<Role> getRoleByName(final Set<String> role) {
		List<Role> roles = new ArrayList<Role>();
		List<RoleEntity> roleEntities = roleRepository.findByNameIn(role);
		if (ValidatorUtil.isNull(roleEntities)) {
			throw new RequestValidationException(messageUtil.getAttributeInvalid("role", role + ""));
		}
		for (RoleEntity roleEntity : roleEntities) {
			if (Status.ACTIVE.getStatusEntity().getStatus()
					.equalsIgnoreCase(roleEntity.getStatusEntity().getStatus())) {
				roles.add(RoleConversionUtil.toRole(roleEntity));
			}
		}
		return roles;
	}
}
