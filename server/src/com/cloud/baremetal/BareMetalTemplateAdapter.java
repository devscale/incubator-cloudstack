// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.baremetal;

import java.util.Date;
import java.util.List;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.cloudstack.api.command.user.iso.DeleteIsoCmd;
import org.apache.cloudstack.api.command.user.iso.RegisterIsoCmd;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenterVO;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.VMTemplateHostVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.TemplateProfile;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.template.TemplateAdapter;
import com.cloud.template.TemplateAdapterBase;
import com.cloud.user.Account;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
@Local(value=TemplateAdapter.class)
public class BareMetalTemplateAdapter extends TemplateAdapterBase implements TemplateAdapter {
	private final static Logger s_logger = Logger.getLogger(BareMetalTemplateAdapter.class);
	@Inject HostDao _hostDao;
	@Inject ResourceManager _resourceMgr;

    @Override
    public String getName() {
        return TemplateAdapterType.BareMetal.getName();
    }

	@Override
	public TemplateProfile prepare(RegisterTemplateCmd cmd) throws ResourceAllocationException {
		TemplateProfile profile = super.prepare(cmd);
		
		if (profile.getZoneId() == null || profile.getZoneId() == -1) {
			List<DataCenterVO> dcs = _dcDao.listAllIncludingRemoved();
			for (DataCenterVO dc : dcs) {
				List<HostVO> pxeServers = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.PxeServer, dc.getId());
				if (pxeServers.size() == 0) {
					throw new CloudRuntimeException("Please add PXE server before adding baremetal template in zone " + dc.getName());
				}
			}
		} else {
			List<HostVO> pxeServers = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.PxeServer, profile.getZoneId());
			if (pxeServers.size() == 0) {
				throw new CloudRuntimeException("Please add PXE server before adding baremetal template in zone " + profile.getZoneId());
			}
		}
		
		return profile;
	}
	
	@Override
	public TemplateProfile prepare(RegisterIsoCmd cmd) throws ResourceAllocationException {
		throw new CloudRuntimeException("Baremetal doesn't support ISO template");
	}
	
	private void templateCreateUsage(VMTemplateVO template, HostVO host) {
		if (template.getAccountId() != Account.ACCOUNT_ID_SYSTEM) {
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_TEMPLATE_CREATE, template.getAccountId(), host.getDataCenterId(),
                    template.getId(), template.getName(), null, template.getSourceTemplateId(), 0L,
                    template.getClass().getName(), template.getUuid());
		}
	}
	
	@Override
	public VMTemplateVO create(TemplateProfile profile) {
		VMTemplateVO template = persistTemplate(profile);
		Long zoneId = profile.getZoneId();
		
		/* There is no secondary storage vm for baremetal, we use pxe server id.
		 * Tempalte is not bound to pxeserver right now, and we assume the pxeserver
		 * cannot be removed once it was added. so we use host id of first found pxe
		 * server as reference in template_host_ref.
		 * This maybe a FIXME in future.
		 */
		VMTemplateHostVO vmTemplateHost = null;
		if (zoneId == null || zoneId == -1) {
			List<DataCenterVO> dcs = _dcDao.listAllIncludingRemoved();
			for (DataCenterVO dc : dcs) {
				HostVO pxe = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.PxeServer, dc.getId()).get(0);

				vmTemplateHost = _tmpltHostDao.findByHostTemplate(dc.getId(), template.getId());
				if (vmTemplateHost == null) {
					vmTemplateHost = new VMTemplateHostVO(pxe.getId(), template.getId(), new Date(), 100,
							Status.DOWNLOADED, null, null, null, null, template.getUrl());
					_tmpltHostDao.persist(vmTemplateHost);
					templateCreateUsage(template, pxe);
				}
			}
		} else {
			HostVO pxe = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.PxeServer, zoneId).get(0);
			vmTemplateHost = new VMTemplateHostVO(pxe.getId(), template.getId(), new Date(), 100,
					Status.DOWNLOADED, null, null, null, null, template.getUrl());
			_tmpltHostDao.persist(vmTemplateHost);
			templateCreateUsage(template, pxe);
		}
		
		_resourceLimitMgr.incrementResourceCount(profile.getAccountId(), ResourceType.template);
		return template;
	}

	public TemplateProfile prepareDelete(DeleteIsoCmd cmd) {
		throw new CloudRuntimeException("Baremetal doesn't support ISO, how the delete get here???");
	}
	
	@Override @DB
	public boolean delete(TemplateProfile profile) {
		VMTemplateVO template = (VMTemplateVO)profile.getTemplate();
    	Long templateId = template.getId();
    	boolean success = true;
    	String zoneName;
    	boolean isAllZone;
    	
    	if (!template.isCrossZones() && profile.getZoneId() != null) {
    		isAllZone = false;
    		zoneName = profile.getZoneId().toString();
    	} else {
    		zoneName = "all zones";
    		isAllZone = true;
    	}
    	
    	s_logger.debug("Attempting to mark template host refs for template: " + template.getName() + " as destroyed in zone: " + zoneName);
    	Account account = _accountDao.findByIdIncludingRemoved(template.getAccountId());
    	String eventType = EventTypes.EVENT_TEMPLATE_DELETE;
    	List<VMTemplateHostVO> templateHostVOs = _tmpltHostDao.listByTemplateId(templateId);
    	
		for (VMTemplateHostVO vo : templateHostVOs) {
			VMTemplateHostVO lock = null;
			try {
				HostVO pxeServer = _hostDao.findById(vo.getHostId());
				if (!isAllZone && pxeServer.getDataCenterId() != profile.getZoneId()) {
					continue;
				}

				lock = _tmpltHostDao.acquireInLockTable(vo.getId());
				if (lock == null) {
					s_logger.debug("Failed to acquire lock when deleting templateHostVO with ID: " + vo.getId());
					success = false;
					break;
				}

				vo.setDestroyed(true);
				_tmpltHostDao.update(vo.getId(), vo);
				VMTemplateZoneVO templateZone = _tmpltZoneDao.findByZoneTemplate(pxeServer.getDataCenterId(), templateId);
				if (templateZone != null) {
					_tmpltZoneDao.remove(templateZone.getId());
				}

                UsageEventUtils.publishUsageEvent(eventType, account.getId(), pxeServer.getDataCenterId(),
                        templateId, null, template.getClass().getName(), template.getUuid());
			} finally {
				if (lock != null) {
					_tmpltHostDao.releaseFromLockTable(lock.getId());
				}
			}
		}
    	
    	s_logger.debug("Successfully marked template host refs for template: " + template.getName() + " as destroyed in zone: " + zoneName);
	
    	// If there are no more non-destroyed template host entries for this template, delete it
		if (success && (_tmpltHostDao.listByTemplateId(templateId).size() == 0)) {
			long accountId = template.getAccountId();
			
			VMTemplateVO lock = _tmpltDao.acquireInLockTable(templateId);

			try {
				if (lock == null) {
					s_logger.debug("Failed to acquire lock when deleting template with ID: " + templateId);
					success = false;
				} else if (_tmpltDao.remove(templateId)) {
					// Decrement the number of templates
				    _resourceLimitMgr.decrementResourceCount(accountId, ResourceType.template);
				}

			} finally {
				if (lock != null) {
					_tmpltDao.releaseFromLockTable(lock.getId());
				}
			}
			s_logger.debug("Removed template: " + template.getName() + " because all of its template host refs were marked as destroyed.");
		}
		
    	return success;
	}
}
