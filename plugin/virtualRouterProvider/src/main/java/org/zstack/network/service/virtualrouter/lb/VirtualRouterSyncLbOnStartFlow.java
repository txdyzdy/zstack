package org.zstack.network.service.virtualrouter.lb;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.header.core.Completion;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.vm.VmNicInventory;
import org.zstack.header.vm.VmNicVO;
import org.zstack.header.vm.VmNicVO_;
import org.zstack.network.service.lb.*;
import org.zstack.network.service.vip.VipInventory;
import org.zstack.network.service.vip.VipVO;
import org.zstack.network.service.vip.VipVO_;
import org.zstack.network.service.virtualrouter.*;
import org.zstack.network.service.virtualrouter.VirtualRouterConstant.Param;
import org.zstack.network.service.virtualrouter.vip.VirtualRouterVipBackend;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.function.Function;

import javax.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Created by frank on 8/17/2015.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class VirtualRouterSyncLbOnStartFlow implements Flow {
    @Autowired
    private VirtualRouterManager vrMgr;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private VirtualRouterLoadBalancerBackend bkd;
    @Autowired
    protected VirtualRouterVipBackend vipExt;

    private LoadBalancerStruct makeStruct(LoadBalancerVO vo) {
        LoadBalancerStruct struct = new LoadBalancerStruct();
        struct.setLb(LoadBalancerInventory.valueOf(vo));

        if (!vo.getVmNicRefs().isEmpty()) {
            List<String> activeNics = CollectionUtils.transformToList(vo.getVmNicRefs(), new Function<String, LoadBalancerVmNicRefVO>() {
                @Override
                public String call(LoadBalancerVmNicRefVO arg) {
                    return arg.getStatus() == LoadBalancerVmNicStatus.Active || arg.getStatus() == LoadBalancerVmNicStatus.Pending ? arg.getVmNicUuid() : null;
                }
            });

            if (!activeNics.isEmpty()) {
                SimpleQuery<VmNicVO> nq = dbf.createQuery(VmNicVO.class);
                nq.add(VmNicVO_.uuid, Op.IN, activeNics);
                List<VmNicVO> nics = nq.list();
                struct.setVmNics(VmNicInventory.valueOf(nics));
            } else {
                struct.setVmNics(new ArrayList<VmNicInventory>());
            }
        } else {
            struct.setVmNics(new ArrayList<VmNicInventory>());
        }

        struct.setListeners(LoadBalancerListenerInventory.valueOf(vo.getListeners()));

        return struct;
    }

    @Override
    public void run(final FlowTrigger outterTrigger, final Map data) {
        final VirtualRouterVmInventory vr = (VirtualRouterVmInventory) data.get(VirtualRouterConstant.Param.VR.toString());
        final VmNicInventory guestNic = vr.getGuestNic();
        if (!vrMgr.isL3NetworkNeedingNetworkServiceByVirtualRouter(guestNic.getL3NetworkUuid(), LoadBalancerConstants.LB_NETWORK_SERVICE_TYPE_STRING)) {
            outterTrigger.next();
            return;
        }

        if (VirtualRouterSystemTags.DEDICATED_ROLE_VR.hasTag(vr.getUuid()) && !VirtualRouterSystemTags.VR_LB_ROLE.hasTag(vr.getUuid())) {
            outterTrigger.next();
            return;
        }

        new VirtualRouterRoleManager().makeLoadBalancerRole(vr.getUuid());

        final List<LoadBalancerVO> lbs = new Callable<List<LoadBalancerVO>>() {
            @Override
            @Transactional(readOnly = true)
            public List<LoadBalancerVO> call() {
                String sql = "select lb from LoadBalancerVO lb, LoadBalancerVmNicRefVO lbref, VmNicVO nic, L3NetworkVO l3" +
                        " where lb.uuid = lbref.loadBalancerUuid and lbref.vmNicUuid = nic.uuid and nic.l3NetworkUuid = l3.uuid" +
                        " and l3.uuid = :l3uuid and lb.state = :state and lb.uuid not in (select t.resourceUuid from SystemTagVO t" +
                        " where t.tag = :tag and t.resourceType = :rtype)";

                TypedQuery<LoadBalancerVO>  vq = dbf.getEntityManager().createQuery(sql, LoadBalancerVO.class);

                if (!data.containsKey(Param.IS_NEW_CREATED.toString())) {
                    // start/reboot the vr, handle the case that it is the separate lb vr
                    sql = "select ref.loadBalancerUuid from VirtualRouterLoadBalancerRefVO ref where ref.virtualRouterVmUuid = :vruuid";
                    TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
                    q.setParameter("vruuid", vr.getUuid());
                    List<String> lbuuids = q.getResultList();

                    if (!lbuuids.isEmpty()) {
                        sql = "select lb from LoadBalancerVO lb, LoadBalancerVmNicRefVO lbref, VmNicVO nic, L3NetworkVO l3" +
                                " where lb.uuid = lbref.loadBalancerUuid and lbref.vmNicUuid = nic.uuid and nic.l3NetworkUuid = l3.uuid" +
                                " and l3.uuid = :l3uuid and lb.state = :state and lb.uuid not in (select t.resourceUuid from SystemTagVO t" +
                                " where t.tag = :tag and t.resourceType = :rtype and t.resourceUuid not in (:mylbs))";
                        vq = dbf.getEntityManager().createQuery(sql, LoadBalancerVO.class);
                        vq.setParameter("mylbs", lbuuids);
                    }
                }

                vq.setParameter("tag", LoadBalancerSystemTags.SEPARATE_VR.getTagFormat());
                vq.setParameter("rtype", LoadBalancerVO.class.getSimpleName());
                vq.setParameter("state", LoadBalancerState.Enabled);
                vq.setParameter("l3uuid", guestNic.getL3NetworkUuid());
                return vq.getResultList();
            }
        }.call();

        if (lbs.isEmpty()) {
            outterTrigger.next();
            return;
        }

        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("sync-lb-on-vr-%s", vr.getUuid()));
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "create-vip-for-lbs";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        SimpleQuery<VipVO> q = dbf.createQuery(VipVO.class);
                        q.add(VipVO_.uuid, Op.IN, CollectionUtils.transformToList(lbs, new Function<String, LoadBalancerVO>() {
                            @Override
                            public String call(LoadBalancerVO arg) {
                                return arg.getVipUuid();
                            }
                        }));
                        List<VipVO> vipvos = q.list();

                        vipExt.createVipOnVirtualRouterVm(vr, VipInventory.valueOf(vipvos), new Completion(trigger) {
                            @Override
                            public void success() {
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "create-lbs";

                    @Override
                    public void run(final FlowTrigger trigger, final Map data) {
                        List<LoadBalancerStruct> structs = new ArrayList<LoadBalancerStruct>();
                        for (LoadBalancerVO vo : lbs) {
                            structs.add(makeStruct(vo));
                        }

                        bkd.syncOnStart(vr, structs, new Completion(trigger) {
                            @Override
                            public void success() {
                                List<VirtualRouterLoadBalancerRefVO> refs = new ArrayList<VirtualRouterLoadBalancerRefVO>();
                                for (LoadBalancerVO vo : lbs) {
                                    SimpleQuery<VirtualRouterLoadBalancerRefVO> q = dbf.createQuery(VirtualRouterLoadBalancerRefVO.class);
                                    q.add(VirtualRouterLoadBalancerRefVO_.loadBalancerUuid, Op.EQ, vo.getUuid());
                                    q.add(VirtualRouterLoadBalancerRefVO_.virtualRouterVmUuid, Op.EQ, vr.getUuid());
                                    if (!q.isExists()) {
                                        VirtualRouterLoadBalancerRefVO ref = new VirtualRouterLoadBalancerRefVO();
                                        ref.setLoadBalancerUuid(vo.getUuid());
                                        ref.setVirtualRouterVmUuid(vr.getUuid());
                                        refs.add(ref);
                                    }
                                }

                                dbf.persistCollection(refs);
                                data.put(VirtualRouterSyncLbOnStartFlow.class, refs);
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(outterTrigger) {
                    @Override
                    public void handle(Map data) {
                        outterTrigger.next();
                    }
                });

                error(new FlowErrorHandler(outterTrigger) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        outterTrigger.fail(errCode);
                    }
                });
            }
        }).start();
    }

    @Override
    public void rollback(FlowTrigger trigger, Map data) {
        List<VirtualRouterLoadBalancerRefVO> refs = (List<VirtualRouterLoadBalancerRefVO>) data.get(VirtualRouterSyncLbOnStartFlow.class);
        if (refs != null) {
            dbf.removeCollection(refs, VirtualRouterLoadBalancerRefVO.class);
        }

        trigger.rollback();
    }
}
