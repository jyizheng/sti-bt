/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.marshall.exts;

import org.infinispan.commands.CancelCommand;
import org.infinispan.commands.CreateCacheCommand;
import org.infinispan.commands.RemoveCacheCommand;
import org.infinispan.commands.TopologyAffectedCommand;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.read.DistributedExecuteCommand;
import org.infinispan.commands.read.MapCombineCommand;
import org.infinispan.commands.read.ReduceCommand;
import org.infinispan.commands.remote.CacheRpcCommand;
import org.infinispan.commands.remote.ClusteredGetCommand;
import org.infinispan.commands.remote.ConfigurationStateCommand;
import org.infinispan.commands.remote.DataPlacementCommand;
import org.infinispan.commands.remote.GMUClusteredGetCommand;
import org.infinispan.commands.remote.GarbageCollectorControlCommand;
import org.infinispan.commands.remote.MultipleRpcCommand;
import org.infinispan.commands.remote.ReconfigurableProtocolCommand;
import org.infinispan.commands.remote.SingleRpcCommand;
import org.infinispan.commands.remote.recovery.CompleteTransactionCommand;
import org.infinispan.commands.remote.recovery.GetInDoubtTransactionsCommand;
import org.infinispan.commands.remote.recovery.GetInDoubtTxInfoCommand;
import org.infinispan.commands.remote.recovery.TxCompletionNotificationCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.tx.GMUCommitCommand;
import org.infinispan.commands.tx.GMUPrepareCommand;
import org.infinispan.commands.tx.VersionedCommitCommand;
import org.infinispan.commands.tx.VersionedPrepareCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderCommitCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderGMUCommitCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderGMUPrepareCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderNonVersionedPrepareCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderRollbackCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderVersionedCommitCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderVersionedPrepareCommand;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.factories.KnownComponentNames;
import org.infinispan.io.ExposedByteArrayOutputStream;
import org.infinispan.io.UnsignedNumeric;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.marshall.AbstractExternalizer;
import org.infinispan.marshall.BufferSizePredictor;
import org.infinispan.marshall.Ids;
import org.infinispan.marshall.StreamingMarshaller;
import org.infinispan.marshall.jboss.ExtendedRiverUnmarshaller;
import org.infinispan.statetransfer.StateRequestCommand;
import org.infinispan.statetransfer.StateResponseCommand;
import org.infinispan.util.Util;
import org.infinispan.xsite.XSiteAdminCommand;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Set;

/**
 * Externalizer in charge of marshalling cache specific commands. At read time,
 * this marshaller is able to locate the right cache marshaller and provide
 * it any externalizers implementations that follow.
 *
 * @author Galder Zamarreño
 * @since 5.1
 */
public final class CacheRpcCommandExternalizer extends AbstractExternalizer<CacheRpcCommand> {
   private final GlobalComponentRegistry gcr;
   private final ReplicableCommandExternalizer cmdExt;
   private final StreamingMarshaller globalMarshaller;

   public CacheRpcCommandExternalizer(GlobalComponentRegistry gcr, ReplicableCommandExternalizer cmdExt) {
      this.cmdExt = cmdExt;
      this.gcr = gcr;
      //Cache this locally to avoid having to look it up often:
      this.globalMarshaller = gcr.getComponent(StreamingMarshaller.class, KnownComponentNames.GLOBAL_MARSHALLER);
   }

   @Override
   public Set<Class<? extends CacheRpcCommand>> getTypeClasses() {
      Set<Class<? extends CacheRpcCommand>> coreCommands = Util.asSet(MapCombineCommand.class,
               ReduceCommand.class, DistributedExecuteCommand.class, LockControlCommand.class,
               StateRequestCommand.class, StateResponseCommand.class, ClusteredGetCommand.class,
               MultipleRpcCommand.class, SingleRpcCommand.class, CommitCommand.class,
               PrepareCommand.class, RollbackCommand.class, RemoveCacheCommand.class,
               TxCompletionNotificationCommand.class, GetInDoubtTransactionsCommand.class,
               GetInDoubtTxInfoCommand.class, CompleteTransactionCommand.class,
               VersionedPrepareCommand.class, CreateCacheCommand.class, CancelCommand.class,
               VersionedCommitCommand.class, XSiteAdminCommand.class, TotalOrderNonVersionedPrepareCommand.class,
               TotalOrderVersionedPrepareCommand.class, TotalOrderCommitCommand.class,
               TotalOrderVersionedCommitCommand.class, TotalOrderRollbackCommand.class, DataPlacementCommand.class,
               GMUPrepareCommand.class, GMUCommitCommand.class, GMUClusteredGetCommand.class, GarbageCollectorControlCommand.class,
               TotalOrderGMUPrepareCommand.class, TotalOrderGMUCommitCommand.class, ReconfigurableProtocolCommand.class,
               ConfigurationStateCommand.class);
      // Only interested in cache specific replicable commands
      coreCommands.addAll(gcr.getModuleProperties().moduleCacheRpcCommands());
      return coreCommands;
   }

   @Override
   public void writeObject(ObjectOutput output, CacheRpcCommand command) throws IOException {
      cmdExt.writeCommandHeader(output, command);

      String cacheName = command.getCacheName();
      output.writeUTF(cacheName);
      StreamingMarshaller marshaller = getCacheMarshaller(cacheName);

      // Take the cache marshaller and generate the payload for the rest of
      // the command using that cache marshaller and the write the bytes in
      // the original payload.
      ExposedByteArrayOutputStream os = marshallParameters(command, marshaller);
      UnsignedNumeric.writeUnsignedInt(output, os.size());
      // Do not rely on the raw buffer's length which is likely to be much longer!
      output.write(os.getRawBuffer(), 0, os.size());
      if (command instanceof TopologyAffectedCommand) {
         output.writeInt(((TopologyAffectedCommand) command).getTopologyId());
      }
   }

   private ExposedByteArrayOutputStream marshallParameters(
         CacheRpcCommand cmd, StreamingMarshaller marshaller) throws IOException {
      BufferSizePredictor sizePredictor = marshaller.getBufferSizePredictor(cmd);
      int estimatedSize = sizePredictor.nextSize(cmd);
      ExposedByteArrayOutputStream baos = new ExposedByteArrayOutputStream(estimatedSize);
      ObjectOutput output = marshaller.startObjectOutput(baos, true, estimatedSize);
      try {
         cmdExt.writeCommandParameters(output, cmd);
      } finally {
         marshaller.finishObjectOutput(output);
      }
      return baos;
   }

   @Override
   public CacheRpcCommand readObject(ObjectInput input) throws IOException, ClassNotFoundException {
      byte type = input.readByte();
      byte methodId = (byte) input.readShort();

      String cacheName = input.readUTF();
      StreamingMarshaller marshaller = getCacheMarshaller(cacheName);

      byte[] paramsRaw = new byte[UnsignedNumeric.readUnsignedInt(input)];
      // This is not ideal cos it forces the code to read all parameters into
      // memory and then splitting them, potentially leading to excessive
      // buffering. An alternative solution is shown in SharedStreamMultiMarshallerTest
      // but it requires some special treatment - iow, hacking :)
      input.readFully(paramsRaw);
      ByteArrayInputStream is = new ByteArrayInputStream(paramsRaw, 0, paramsRaw.length);
      ObjectInput paramsInput = marshaller.startObjectInput(is, true);
      // Not ideal, but the alternative (without changing API), would have been
      // using thread locals which are expensive to retrieve.
      // Remember that the aim with externalizers is for them to be stateless.
      if (paramsInput instanceof ExtendedRiverUnmarshaller)
         ((ExtendedRiverUnmarshaller) paramsInput).setInfinispanMarshaller(marshaller);

      try {
         Object[] args = cmdExt.readParameters(paramsInput);
         CacheRpcCommand cacheRpcCommand = cmdExt.fromStream(methodId, args, type, cacheName);
         if (cacheRpcCommand instanceof TopologyAffectedCommand) {
            int topologyId = input.readInt();
            ((TopologyAffectedCommand)cacheRpcCommand).setTopologyId(topologyId);
         }
         return cacheRpcCommand;
      } finally {
         marshaller.finishObjectInput(paramsInput);
      }
   }

   @Override
   public Integer getId() {
      return Ids.CACHE_RPC_COMMAND;
   }

   private StreamingMarshaller getCacheMarshaller(String cacheName) {
      ComponentRegistry registry = gcr.getNamedComponentRegistry(cacheName);
      if (registry == null || registry.getStatus() != ComponentStatus.RUNNING) {
         // When starting, even though the command is directed at a cache,
         // it could happen that the cache is not yet started, so fallback on
         // global marshaller.

         // The reason cache and global marshallers are different is cos right
         // now they could be associated with different classloaders. There are
         // situations when the cache marshaller might not yet be available
         // (i.e. StateRequestCommand), so this fallback is basically saying:
         // "when cache is starting, if you can't find the cache marshaller,
         // use the global marshaller and the global classloader"
         return globalMarshaller;
      } else {
         return registry.getCacheMarshaller();
      }
   }

}
