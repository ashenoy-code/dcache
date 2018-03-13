/*
COPYRIGHT STATUS:
Dec 1st 2001, Fermi National Accelerator Laboratory (FNAL) documents and
software are sponsored by the U.S. Department of Energy under Contract No.
DE-AC02-76CH03000. Therefore, the U.S. Government retains a  world-wide
non-exclusive, royalty-free license to publish or reproduce these documents
and software for U.S. Government purposes.  All documents and software
available from this server are protected under the U.S. and Foreign
Copyright Laws, and FNAL reserves all rights.

Distribution of the software available from this server is free of
charge subject to the user following the terms of the Fermitools
Software Legal Information.

Redistribution and/or modification of the software shall be accompanied
by the Fermitools Software Legal Information  (including the copyright
notice).

The user is asked to feed back problems, benefits, and/or suggestions
about the software to the Fermilab Software Providers.

Neither the name of Fermilab, the  URA, nor the names of the contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

DISCLAIMER OF LIABILITY (BSD):

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED  WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED  WARRANTIES OF MERCHANTABILITY AND FITNESS
FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL FERMILAB,
OR THE URA, OR THE U.S. DEPARTMENT of ENERGY, OR CONTRIBUTORS BE LIABLE
FOR  ANY  DIRECT, INDIRECT,  INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
OF SUBSTITUTE  GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY  OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT  OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE  POSSIBILITY OF SUCH DAMAGE.

Liabilities of the Government:

This software is provided by URA, independent from its Prime Contract
with the U.S. Department of Energy. URA is acting independently from
the Government and in its own private capacity and is not acting on
behalf of the U.S. Government, nor as its contractor nor its agent.
Correspondingly, it is understood and agreed that the U.S. Government
has no connection to this software and in no manner whatsoever shall
be liable for nor assume any responsibility or obligation for any claim,
cost, or damages arising out of or resulting from the use of the software
available from this server.

Export Control:

All documents and software available from this server are subject to U.S.
export control laws.  Anyone downloading information from this server is
obligated to secure any necessary Government licenses before exporting
documents or software obtained from this server.
 */
package org.dcache.restful.resources.pool;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import java.util.List;
import java.util.stream.Collectors;

import diskCacheV111.poolManager.PoolSelectionUnit;

import org.dcache.poolmanager.PoolMonitor;
import org.dcache.restful.providers.pool.PoolGroupInfo;
import org.dcache.restful.providers.selection.PoolGroup;
import org.dcache.restful.services.pool.PoolInfoService;
import org.dcache.restful.util.HttpServletRequests;

/**
 * <p>RESTful API to the {@link PoolInfoService} service.</p>
 *
 * @version v1.0
 */
@Component
@Api(value = "poolmanager", authorizations = {@Authorization("basicAuth")})
@Path("/poolgroups")
public final class PoolGroupInfoResources {
    @Context
    private HttpServletRequest request;

    @Inject
    private PoolInfoService service;

    @Inject
    private PoolMonitor poolMonitor;

    @GET
    @ApiOperation("Get a list of poolgroups.  Requires admin role.")
    @ApiResponses({
        @ApiResponse(code = 403, message = "Pool group info only accessible to admin users."),
    })
    @Produces(MediaType.APPLICATION_JSON)
    public List<PoolGroup> getPoolGroups() {
        if (!HttpServletRequests.isAdmin(request)) {
            throw new ForbiddenException(
                            "Pool group info only accessible to admin users.");
        }

        PoolSelectionUnit psu = poolMonitor.getPoolSelectionUnit();

        return psu.getPoolGroups().values()
                  .stream()
                  .map((g) -> new PoolGroup(g.getName(), psu))
                  .collect(Collectors.toList());
    }


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation("Get information about a poolgroup.  Requires admin role.")
    @ApiResponses({
        @ApiResponse(code = 403, message = "Pool group info only accessible to admin users."),
    })
    @Path("/{group}")
    public PoolGroup getPoolGroup(@ApiParam("The poolgroup to be described.")
                                  @PathParam("group") String group) {
        if (!HttpServletRequests.isAdmin(request)) {
            throw new ForbiddenException(
                            "Pool group info only accessible to admin users.");
        }

        return new PoolGroup(group, poolMonitor.getPoolSelectionUnit());
    }

    @GET
    @Path("/{group}/pools")
    @ApiOperation("Get a list of pools that are a member of a poolgroup.  If no "
            + "poolgroup is specified then all pools are listed.")
    @Produces(MediaType.APPLICATION_JSON)
    public String[] getPoolsOfGroup(@ApiParam("The poolgroup to be described.")
                                    @PathParam("group") String group) {
        if (group == null) {
            return service.listPools();
        }

        return service.listPools(group);
    }


    @GET
    @ApiOperation("Get usage metadata about a specific poolgroup.  Requires "
            + "admin role.")
    @ApiResponses({
        @ApiResponse(code = 403, message = "Pool group info only accessible to admin users."),
    })
    @Path("/{group}/usage")
    @Produces(MediaType.APPLICATION_JSON)
    public PoolGroupInfo getGroupUsage(@ApiParam("The poolgroup to be described.")
                                       @PathParam("group") String group) {
        if (!HttpServletRequests.isAdmin(request)) {
            throw new ForbiddenException(
                            "Pool group info only accessible to admin users.");
        }

        PoolGroupInfo info = new PoolGroupInfo();

        service.getGroupCellInfos(group, info);

        return info;
    }


    @GET
    @ApiOperation("Get pool activity information about pools of a specific poolgroup.  "
            + "Requires admin role.")
    @ApiResponses({
        @ApiResponse(code = 403, message = "Pool group info only accessible to admin users."),
    })
    @Path("/{group}/queues")
    @Produces(MediaType.APPLICATION_JSON)
    public PoolGroupInfo getQueueInfo(@ApiParam("The poolgroup to be described.")
                                      @PathParam("group") String group) {
        if (!HttpServletRequests.isAdmin(request)) {
            throw new ForbiddenException(
                            "Pool group info only accessible to admin users.");
        }

        PoolGroupInfo info = new PoolGroupInfo();

        service.getGroupQueueInfos(group, info);

        return info;
    }


    @GET
    @ApiOperation("Get space information about pools of a specific poolgroup.  Requires "
            + "admin role.")
    @ApiResponses({
        @ApiResponse(code = 403, message = "Pool group info only accessible to admin users."),
    })
    @Path("/{group}/space")
    @Produces(MediaType.APPLICATION_JSON)
    public PoolGroupInfo getSpaceInfo(@ApiParam("The poolgroup to be described.")
                                      @PathParam("group") String group) {
        if (!HttpServletRequests.isAdmin(request)) {
            throw new ForbiddenException(
                            "Pool group info only accessible to admin users.");
        }

        PoolGroupInfo info = new PoolGroupInfo();

        service.getGroupSpaceInfos(group, info);

        return info;
    }


    @GET
    @ApiOperation("Get aggregated pool activity histogram information from pools in a specific poolgroup.  "
            + "Requires admin role.")
    @Path("/{group}/histograms/queues")
    @Produces(MediaType.APPLICATION_JSON)
    public PoolGroupInfo getQueueHistograms(@ApiParam("The poolgroup to be described.")
                                            @PathParam("group") String group) {
        PoolGroupInfo info = new PoolGroupInfo();

        service.getQueueStat(group, info);

        return info;
    }


    @GET
    @ApiOperation("Get aggregated file statistics histogram information from pools in a specific poolgroup.  Requires "
            + "admin role.")
    @ApiResponses({
        @ApiResponse(code = 403, message = "Pool group info only accessible to admin users."),
    })
    @Path("/{group}/histograms/files")
    @Produces(MediaType.APPLICATION_JSON)
    public PoolGroupInfo getFilesHistograms(@ApiParam("The poolgroup to be described.")
                                            @PathParam("group") String group) {
        PoolGroupInfo info = new PoolGroupInfo();

        service.getFileStat(group, info);

        return info;
    }
}
