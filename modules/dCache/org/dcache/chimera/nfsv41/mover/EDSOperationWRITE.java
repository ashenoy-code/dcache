package org.dcache.chimera.nfsv41.mover;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;

import org.acplt.oncrpc.server.OncRpcCallInformation;
import org.apache.log4j.Logger;
import org.dcache.chimera.FileSystemProvider;
import org.dcache.chimera.FsInode;
import org.dcache.chimera.IOHimeraFsException;
import org.dcache.chimera.nfs.ExportFile;
import org.dcache.chimera.nfs.v4.AbstractNFSv4Operation;
import org.dcache.chimera.nfs.v4.CompoundArgs;
import org.dcache.chimera.nfs.v4.HimeraNFS4Exception;
import org.dcache.chimera.nfs.v4.NFSv4OperationResult;
import org.dcache.chimera.nfs.v4.WRITE4res;
import org.dcache.chimera.nfs.v4.WRITE4resok;
import org.dcache.chimera.nfs.v4.count4;
import org.dcache.chimera.nfs.v4.nfs4_prot;
import org.dcache.chimera.nfs.v4.nfs_argop4;
import org.dcache.chimera.nfs.v4.nfs_opnum4;
import org.dcache.chimera.nfs.v4.nfsstat4;
import org.dcache.chimera.nfs.v4.stable_how4;
import org.dcache.chimera.nfs.v4.uint32_t;
import org.dcache.chimera.nfs.v4.verifier4;

import org.dcache.pool.movers.MoverProtocol;

public class EDSOperationWRITE extends AbstractNFSv4Operation {

    private static final Logger _log = Logger.getLogger(EDSOperationWRITE.class.getName());

     private final Map<FsInode, MoverBridge> _activeIO;
     private static final int INC_SPACE = (50 * 1024 * 1024);


    public EDSOperationWRITE(FileSystemProvider fs, OncRpcCallInformation call$, CompoundArgs fh, nfs_argop4 args, Map<FsInode, MoverBridge> activeIO, ExportFile exports) {
        super(fs, exports, call$, fh, args, nfs_opnum4.OP_WRITE);
        _activeIO = activeIO;
        if(_log.isDebugEnabled() ) {
            _log.debug("NFS Request DSWRITE from: " + _callInfo.peerAddress.getHostAddress() );
        }
    }

    @Override
    public NFSv4OperationResult process() {

        WRITE4res res = new WRITE4res();

        try {

            MoverBridge moverBridge = _activeIO.get(_fh.currentInode());
            if( (moverBridge.getIoMode() & MoverProtocol.WRITE) != MoverProtocol.WRITE ) {
                throw new HimeraNFS4Exception(nfsstat4.NFS4ERR_PERM, "an attermp to write without IO mode enabled");
            }

            long offset = _args.opwrite.offset.value.value;
            int count = _args.opwrite.data.length;

            FileChannel fc = moverBridge.getFileChannel();
            IOWriteFile out = new IOWriteFile(fc);

            if( offset + count > fc.size() ) {
                moverBridge.getAllocator().allocate(INC_SPACE);
            }
            int bytesWritten = out.write(_args.opwrite.data, offset, count);

            if( bytesWritten < 0 ) {
                throw new IOHimeraFsException("IO not allowed");
            }

            moverBridge.getMover().setBytesTransferred(bytesWritten);

            res.status = nfsstat4.NFS4_OK;
            res.resok4 = new WRITE4resok();
            res.resok4.count = new count4( new uint32_t(bytesWritten) );
            res.resok4.committed = stable_how4.FILE_SYNC4;
            res.resok4.writeverf = new verifier4();
            res.resok4.writeverf.value = new byte[nfs4_prot.NFS4_VERIFIER_SIZE];

            _log.debug("MOVER: " + bytesWritten + "@"  +offset +" written, " + _args.opwrite.data.length + " requested.");

        }catch(IOHimeraFsException hioe) {
            _log.debug(hioe.getMessage());
            res.status = nfsstat4.NFS4ERR_IO;
        }catch(HimeraNFS4Exception he) {
            _log.debug(he.getMessage());
            res.status = he.getStatus();
        }catch(IOException ioe) {
            _log.error("DSWRITE: ", ioe);
            res.status = nfsstat4.NFS4ERR_IO;
        }catch(Exception e) {
            _log.error("DSWRITE: ", e);
            res.status = nfsstat4.NFS4ERR_SERVERFAULT;
        }

       _result.opwrite = res;

        return new NFSv4OperationResult(_result, res.status);

    }

    private static class IOWriteFile {

        private final FileChannel _fc;

        public IOWriteFile(FileChannel fc) {
            _fc = fc;
        }

        public int write(byte[] b, long off, long len) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(b, 0, (int)len);
            bb.rewind();
            _fc.position(off);
            return _fc.write(bb);
        }

        public long size() throws IOException {
            return _fc.size();
        }

    }

}
