package io.left.rightmesh.module.core.hello;

import java.nio.ByteBuffer;

import io.left.rightmesh.libcbor.CBOR;
import io.left.rightmesh.libcbor.CborParser;
import io.left.rightmesh.libcbor.rxparser.RxParserException;
import io.left.rightmesh.libdtn.common.data.Bundle;
import io.left.rightmesh.libdtn.common.data.PayloadBlock;
import io.left.rightmesh.libdtn.common.data.blob.UntrackedByteBufferBLOB;
import io.left.rightmesh.libdtn.common.data.blob.WritableBLOB;
import io.left.rightmesh.libdtn.common.data.eid.BaseCLAEID;
import io.left.rightmesh.libdtn.common.data.eid.DTN;
import io.left.rightmesh.libdtn.common.data.eid.EID;
import io.left.rightmesh.libdtn.common.data.eid.EIDFormatException;
import io.left.rightmesh.libdtn.core.api.CoreAPI;
import io.left.rightmesh.libdtn.core.api.RegistrarAPI;
import io.left.rightmesh.libdtn.core.events.LinkLocalEntryUp;
import io.left.rightmesh.libdtn.core.spi.core.CoreModuleSPI;
import io.left.rightmesh.librxbus.RxBus;
import io.left.rightmesh.librxbus.Subscribe;
import io.reactivex.Completable;

/**
 * @author Lucien Loiseau on 13/11/18.
 */
public class CoreModuleHello implements CoreModuleSPI {

    private static final String TAG = "HelloModule";

    private static class RequestException extends Exception {
        RequestException(String msg) {
            super("Request: " + msg);
        }
    }

    private CoreAPI coreAPI;
    private Bundle helloBundle;

    public CoreModuleHello() {
    }

    @Override
    public String getModuleName() {
        return "hello";
    }

    private void initHelloBundle() {
        HelloMessage hello = new HelloMessage(coreAPI.getExtensionManager().getEIDFactory());

        /* add node main EID */
        hello.eids.add(coreAPI.getLocalEID().localEID());

        /* add aliases */
        hello.eids.addAll(coreAPI.getLocalEID().aliases());

        /* get size of hello message for the payload */
        long size = hello.encode().observe()
                .map(ByteBuffer::remaining)
                .reduce(0, (a, b) -> a + b)
                .blockingGet();

        /* serialize the hello message into a BLOB (for payload) */
        UntrackedByteBufferBLOB blobHello = new UntrackedByteBufferBLOB((int) size);
        final WritableBLOB wblob = blobHello.getWritableBLOB();
        hello.encode().observe()
                .map(wblob::write)
                .doOnComplete(wblob::close)
                .subscribe();

        /* create Hello Bundle */
        helloBundle = new Bundle(DTN.NullEID());
        helloBundle.addBlock(new PayloadBlock(blobHello));
    }

    @Override
    public void init(CoreAPI api) {
        this.coreAPI = api;

        initHelloBundle();

        try {
            api.getRegistrar().register("/hello/", (bundle) -> {
                if (bundle.getTagAttachment("cla-origin-iid") != null) {
                    coreAPI.getLogger().i(TAG, "received hello message from: " +
                            bundle.source.getEIDString() +
                            " on BaseCLAEID: " +
                            bundle.<EID>getTagAttachment("cla-origin-iid").getEIDString());
                    CborParser p = CBOR.parser()
                            .cbor_parse_custom_item(
                                    () -> new HelloMessage(api.getExtensionManager().getEIDFactory()),
                                    (__, ___, item) -> {
                                        for (EID eid : item.eids) {
                                            coreAPI.getRoutingEngine().addRoute(
                                                    eid,
                                                    bundle.getTagAttachment("cla-origin-iid"));
                                        }
                                    });

                    bundle.getPayloadBlock().data.observe().subscribe(
                            b -> {
                                try {
                                    while (b.hasRemaining() && !p.isDone()) {
                                        p.read(b);
                                    }
                                } catch (RxParserException rpe) {
                                    api.getLogger().i(TAG, "malformed hello message: " + rpe.getMessage());
                                }
                            });
                } else {
                    coreAPI.getLogger().i(TAG, "received hello message from: " +
                            bundle.source.getEIDString() +
                            " but the BaseCLAEID wasn't tagged - ignoring");
                }

                return Completable.complete();
            });
        } catch (RegistrarAPI.SinkAlreadyRegistered |
                RegistrarAPI.RegistrarDisabled |
                RegistrarAPI.NullArgument re) {
            api.getLogger().e(TAG, "initialization failed: " + re.getMessage());
            return;
        }

        RxBus.register(this);
    }

    /* events are serialized */
    @Subscribe
    public void onEvent(LinkLocalEntryUp up) {
        try {
            BaseCLAEID eid = ((BaseCLAEID) up.channel.channelEID().copy()).setPath("/hello/");
            coreAPI.getLogger().i(TAG, "sending hello message to: " + eid.getEIDString());
            helloBundle.destination = eid;
            up.channel.sendBundle(helloBundle,
                    coreAPI.getExtensionManager().getBlockDataSerializerFactory()
            ).subscribe(
                    i -> {/* ignore */},
                    e -> coreAPI.getLogger().i(
                            TAG, "fail to send hello message: " +
                                    eid.getEIDString()),
                    () -> {/* ignore */}
            );
        } catch (EIDFormatException efe) {
            coreAPI.getLogger().e(TAG, "Cannot append /hello/ to IID: " + up.channel.channelEID() + " reason=" + efe.getMessage());
        }
    }

}