/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.infinispan.tree;

import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.lifecycle.AbstractModuleLifecycle;
import org.infinispan.marshall.AdvancedExternalizer;

import java.util.Map;

/**
 * Module lifecycle callbacks implementation that enables module specific
 * {@link org.infinispan.marshall.AdvancedExternalizer} implementations to be registered.
 *
 * Information about the valid id range can be found <a href="http://community.jboss.org/docs/DOC-16198">here</a>
 *
 * @author Galder Zamarreño
 * @since 5.0
 */
public class LifecycleCallbacks extends AbstractModuleLifecycle {

   @Override
   public void cacheManagerStarting(GlobalComponentRegistry gcr, GlobalConfiguration globalCfg) {
      Map<Integer,AdvancedExternalizer<?>> externalizerMap = globalCfg.serialization().advancedExternalizers();
      externalizerMap.put(1000, new NodeKey.Externalizer());
      externalizerMap.put(1001, new Fqn.Externalizer());
   }

}
