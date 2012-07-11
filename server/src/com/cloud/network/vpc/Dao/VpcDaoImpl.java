// Copyright 2012 Citrix Systems, Inc. Licensed under the
// Apache License, Version 2.0 (the "License"); you may not use this
// file except in compliance with the License.  Citrix Systems, Inc.
// reserves all rights not expressly granted by the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// 
// Automatically generated by addcopyright.py at 04/03/2012
package com.cloud.network.vpc.Dao;

import java.util.List;

import javax.ejb.Local;

import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;


@Local(value = VpcDao.class)
@DB(txn = false)
public class VpcDaoImpl extends GenericDaoBase<VpcVO, Long> implements VpcDao{
    final GenericSearchBuilder<VpcVO, Integer> CountByOfferingId;
    final SearchBuilder<VpcVO> AllFieldsSearch;

    protected VpcDaoImpl() {
        super();
        
        CountByOfferingId = createSearchBuilder(Integer.class);
        CountByOfferingId.select(null, Func.COUNT, CountByOfferingId.entity().getId());
        CountByOfferingId.and("offeringId", CountByOfferingId.entity().getVpcOfferingId(), Op.EQ);
        CountByOfferingId.and("removed", CountByOfferingId.entity().getRemoved(), Op.NULL);
        CountByOfferingId.done();
        
        AllFieldsSearch = createSearchBuilder();
        AllFieldsSearch.and("id", AllFieldsSearch.entity().getId(), Op.EQ);
        AllFieldsSearch.and("state", AllFieldsSearch.entity().getState(), Op.EQ);
        AllFieldsSearch.and("accountId", AllFieldsSearch.entity().getAccountId(), Op.EQ);
        AllFieldsSearch.done();
    }
    
    
    @Override
    public int getVpcCountByOfferingId(long offId) {
        SearchCriteria<Integer> sc = CountByOfferingId.create();
        sc.setParameters("offeringId", offId);
        List<Integer> results = customSearch(sc, null);
        return results.get(0);
    }
    
    @Override
    public Vpc getActiveVpcById(long vpcId) {
        SearchCriteria<VpcVO> sc = AllFieldsSearch.create();
        sc.setParameters("id", vpcId);
        sc.setParameters("state", Vpc.State.Enabled);
        return findOneBy(sc);
    }
    
    @Override
    public List<? extends Vpc> listByAccountId(long accountId) {
        SearchCriteria<VpcVO> sc = AllFieldsSearch.create();
        sc.setParameters("accountId", accountId);
        return listBy(sc, null);
    }
    
    @Override
    public List<VpcVO> listInactiveVpcs() {
        SearchCriteria<VpcVO> sc = AllFieldsSearch.create();
        sc.setParameters("state", Vpc.State.Inactive);
        return listBy(sc, null);
    }
}
