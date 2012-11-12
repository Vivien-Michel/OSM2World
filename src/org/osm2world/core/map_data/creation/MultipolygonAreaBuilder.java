package org.osm2world.core.map_data.creation;

import static java.lang.Double.NaN;
import static java.util.Collections.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.graphview.core.data.MapBasedTagGroup;
import org.openstreetmap.josm.plugins.graphview.core.data.Tag;
import org.openstreetmap.josm.plugins.graphview.core.data.TagGroup;
import org.osm2world.core.map_data.data.MapArea;
import org.osm2world.core.map_data.data.MapNode;
import org.osm2world.core.math.AxisAlignedBoundingBoxXZ;
import org.osm2world.core.math.PolygonWithHolesXZ;
import org.osm2world.core.math.SimplePolygonXZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.osm.data.OSMData;
import org.osm2world.core.osm.data.OSMElement;
import org.osm2world.core.osm.data.OSMMember;
import org.osm2world.core.osm.data.OSMNode;
import org.osm2world.core.osm.data.OSMRelation;
import org.osm2world.core.osm.data.OSMWay;

/**
 * utility class for creating areas from multipolygon relations,
 * including those with non-closed member ways.
 * 
 * Known Limitations:<ul>
 * <li>This cannot reliably handle touching inner rings consisting
 * of non-closed ways.</li>
 * <li>Closed touching rings will not break the calculation,
 * but are represented as multiple touching holes.</li>
 * </ul>
 */
final class MultipolygonAreaBuilder {
	
	/** prevents instantiation */
	private MultipolygonAreaBuilder() { }
	
	/**
	 * Creates areas for a multipolygon relation.
	 * Also adds this area to the adjacent nodes using
	 * {@link MapNode#addAdjacentArea(MapArea)}.
	 * 
	 * @param relation  the multipolygon relation
	 * @param nodeMap   map from {@link OSMNode}s to {@link MapNode}s
	 * 
	 * @return  constructed area(s), multiple areas will be created if there
	 *          is more than one outer ring. Empty for invalid multipolygons.
	 */
	public static final Collection<MapArea> createAreasForMultipolygon(
			OSMRelation relation, Map<OSMNode, MapNode> nodeMap) {
		
		if (isSimpleMultipolygon(relation)) {
			return createAreasForSimpleMultipolygon(relation, nodeMap);
		} else {
			return createAreasForAdvancedMultipolygon(relation, nodeMap);
		}
		
	}

	private static final boolean isSimpleMultipolygon(OSMRelation relation) {
		
		int numberOuters = 0;
		boolean closedWays = true;
		
		for (OSMMember member : relation.relationMembers) {
			
			if ("outer".equals(member.role)) {
				numberOuters += 1;
			}
			
			if (("outer".equals(member.role) || "inner".equals(member.role))
					&& member.member instanceof OSMWay) {
				
				if (!((OSMWay)member.member).isClosed()) {
					closedWays = false;
					break;
				}
				
			}
			
		}
		
		return numberOuters == 1 && closedWays;
		
	}

	/**
	 * handles the common simple case with only one outer way.
	 * Expected to be faster than the more general method
	 * {@link #createAreasForAdvancedMultipolygon(OSMRelation, Map)}
	 * 
	 * @param relation  has to be a simple multipolygon relation
	 * @param nodeMap
	 */
	private static final Collection<MapArea> createAreasForSimpleMultipolygon(
			OSMRelation relation, Map<OSMNode, MapNode> nodeMap) {
		
		assert isSimpleMultipolygon(relation);
		
		OSMElement tagSource = null;
		List<MapNode> outerNodes = null;
		List<List<MapNode>> holes = new ArrayList<List<MapNode>>();
		
		for (OSMMember member : relation.relationMembers) {
			if (member.member instanceof OSMWay) {
				
				OSMWay way = (OSMWay)member.member;
				
				if ("inner".equals(member.role)) {
					
					List<MapNode> hole = new ArrayList<MapNode>(way.nodes.size());
					
					for (OSMNode node : ((OSMWay)member.member).nodes) {
						hole.add(nodeMap.get(node));
						//TODO: add area as adjacent to node for inners' nodes, too?
					}
					
					holes.add(hole);
					
				} else if ("outer".equals(member.role)) {
					
					tagSource = relation.tags.size() > 1 ? relation : way;
					
					outerNodes = new ArrayList<MapNode>(way.nodes.size());
					for (OSMNode node : way.nodes) {
						outerNodes.add(nodeMap.get(node));
					}
					
				}
				
			}
		}
		
		return singleton(new MapArea(tagSource, outerNodes, holes));
		
	}

	private static final Collection<MapArea> createAreasForAdvancedMultipolygon(
			OSMRelation relation, Map<OSMNode, MapNode> nodeMap) {
		
		ArrayList<OSMWay> unassignedWays = new ArrayList<OSMWay>();
		
		/* collect ways */
		
		for (OSMMember member : relation.relationMembers) {
			if (member.member instanceof OSMWay
					&& ("outer".equals(member.role)
							|| "inner".equals(member.role)) ) {
				
				unassignedWays.add((OSMWay)member.member);
				
			}
		}
		
		/* build rings, then polygons from the ways */
		
		List<WayRing> wayRings = buildRings(unassignedWays, nodeMap, true);
		
		if (wayRings != null) {
			
			List<MapNodeRing> mapNodeRings =
					new ArrayList<MapNodeRing>(wayRings.size());
			
			for (WayRing wayRing : wayRings) {
				mapNodeRings.add(wayRing.getMapNodeRing());
			}
						
			return buildPolygonsFromRings(relation, mapNodeRings);
			
		}
		
		return Collections.emptyList();
		
	}

	private static final List<WayRing> buildRings(List<OSMWay> ways,
			Map<OSMNode, MapNode> nodeMap, boolean requireClosedRings) {
		
		List<WayRing> closedRings = new ArrayList<WayRing>();
		List<WayRing> unclosedRings = new ArrayList<WayRing>();
		
		WayRing currentRing = null;
		
		while (ways.size() > 0) {
		
			if (currentRing == null) {
				
				// start a new ring with any unassigned way
				
				OSMWay unassignedWay = ways.remove(ways.size() - 1);
				currentRing = new WayRing(unassignedWay, nodeMap);
				
			} else {
				
				// try to continue the ring by appending a way
				
				OSMWay assignedWay = null;
				
				for (OSMWay way : ways) {
				
					if (currentRing.tryAddWay(way)) {
						assignedWay = way;
						break;
					}
					
				}
				
				if (assignedWay != null) {
					ways.remove(assignedWay);
				} else {
					unclosedRings.add(currentRing);
					currentRing = null;
				}
				
			}
			
			// check whether the ring is closed
			
			if (currentRing != null && currentRing.isClosed()) {
				closedRings.add(currentRing);
				currentRing = null;
			}
		
		}
		
		if (currentRing != null) {
			// the last ring could not be closed
			unclosedRings.add(currentRing);
		}
		
		if (unclosedRings.isEmpty()) {
			return closedRings;
		} else if (!requireClosedRings) {
			List<WayRing> wayRings = closedRings;
			wayRings.addAll(unclosedRings);
			return wayRings;
		} else {
			return null;
		}
		
	}

	/**
	 * @param rings  rings to build polygons from; will be empty afterwards
	 */
	private static final Collection<MapArea> buildPolygonsFromRings(
			OSMRelation relation, List<MapNodeRing> rings) {
		
		Collection<MapArea> finishedPolygons =
				new ArrayList<MapArea>(rings.size() / 2);
		
		/* build polygon */
		
		while (!rings.isEmpty()) {
			
			/* find an outer ring */
			
			MapNodeRing outerRing = null;
			
			for (MapNodeRing candidate : rings) {
				
				boolean containedInOtherRings = false;
				
				for (MapNodeRing otherRing : rings) {
					if (otherRing != candidate
							&& otherRing.contains(candidate)) {
						containedInOtherRings = true;
						break;
					}
				}
				
				if (!containedInOtherRings) {
					outerRing = candidate;
					break;
				}
				
			}
			
			/* find inner rings of that ring */
			
			Collection<MapNodeRing> innerRings = new ArrayList<MapNodeRing>();
			
			for (MapNodeRing wayRing : rings) {
				if (wayRing != outerRing && outerRing.contains(wayRing)) {
					
					boolean containedInOtherRings = false;
					
					for (MapNodeRing otherRing : rings) {
						if (otherRing != wayRing && otherRing != outerRing
								&& otherRing.contains(wayRing)) {
							containedInOtherRings = true;
							break;
						}
					}
					
					if (!containedInOtherRings) {
						innerRings.add(wayRing);
					}
					
				}
			}
			
			/* create a new area and remove the used rings */
			
			List<List<MapNode>> holes = new ArrayList<List<MapNode>>(innerRings.size());
			List<SimplePolygonXZ> holesXZ = new ArrayList<SimplePolygonXZ>(innerRings.size());
			
			for (MapNodeRing innerRing : innerRings) {
				holes.add(innerRing);
				holesXZ.add(innerRing.getPolygon());
			}
			
			MapArea area = new MapArea(relation, outerRing, holes,
					new PolygonWithHolesXZ(outerRing.getPolygon(), holesXZ));
			
			finishedPolygons.add(area);
			
			rings.remove(outerRing);
			rings.removeAll(innerRings);
			
		}
		
		return finishedPolygons;
		
	}
	
	private static final TagGroup COASTLINE_NODE_TAGS = new MapBasedTagGroup(
			new Tag("osm2world:note", "fake node from coastline processing"));
	
	/**
	 * turns all coastline ways into {@link MapArea}s
	 * based on an artificial natural=water multipolygon relation.
	 * 
	 * It relies on the direction-dependent drawing of coastlines.
	 * If coastlines are incomplete, then it is attempted to connect them
	 * to proper rings. One assumption being used is that they are complete
	 * within the file's bounds.
	 * 
	 * This will not always work: It relies on a counterclockwise order
	 * of the coastline fragments around the bounding box center.
	 * If the fragments overlap in this order, results are unreliable.
	 * (Could be solved by using intersections with the bounding box instead
	 * of ways' end nodes for sorting, but this is not currently being done.)
	 */
	public static final Collection<MapArea> createAreasForCoastlines(
			OSMData osmData, Map<OSMNode, MapNode> nodeMap,
			Collection<MapNode> mapNodes, AxisAlignedBoundingBoxXZ fileBoundary) {
		
		long highestRelationId = 0;
		long highestNodeId = 0;
		
		List<OSMWay> coastlineWays = new ArrayList<OSMWay>();
		
		for (OSMWay way : osmData.getWays()) {
			if (way.tags.contains("natural", "coastline")) {
				coastlineWays.add(way);
			}
		}
		
		for (OSMRelation relation : osmData.getRelations()) {
			if (relation.id > highestRelationId) {
				highestRelationId = relation.id;
			}
		}
		
		for (OSMNode node : osmData.getNodes()) {
			if (node.id > highestNodeId) {
				highestNodeId = node.id;
			}
		}
		
		if (!coastlineWays.isEmpty() && fileBoundary != null) {
			
			final VectorXZ center = fileBoundary.center();
			
			final double cornerAngle1 = center.angleTo(fileBoundary.topRight());
			final double cornerAngle2 = center.angleTo(fileBoundary.bottomRight());
			final double cornerAngle3 = center.angleTo(fileBoundary.bottomLeft());
			final double cornerAngle4 = center.angleTo(fileBoundary.topLeft());
			
			/* build rings */
			
			List<WayRing> wayRings = buildRings(coastlineWays, nodeMap, false);
			
			List<MapNodeRing> mapNodeRings = new ArrayList<MapNodeRing>();
			
			/* turn all unclosed rings into one or more outer polygons */

			List<WayRing> unclosedRings = new ArrayList<WayRing>();
			
			for (WayRing wayRing : wayRings) {
				if (wayRing.isClosed()) {
					mapNodeRings.add(wayRing.getMapNodeRing());
				} else {
					unclosedRings.add(wayRing);
				}
			}
			
			Collections.sort(unclosedRings, new Comparator<WayRing>() {
				@Override public int compare(WayRing r1, WayRing r2) {
					double a1 = getRingAngle(center, r1);
					double a2 = getRingAngle(center, r2);
					return Double.compare(a1, a2);
				}
			});
			
			//build one closed outer ring from the unclosed ring fragments
			MapNodeRing outerNodes = new MapNodeRing();
			for (int i = 0; i < unclosedRings.size(); i++) {
				
				WayRing wayRing = unclosedRings.get(i);
				WayRing nextRing = unclosedRings.get((i+1) % unclosedRings.size());
				
				outerNodes.addAll(wayRing.getMapNodeRing());
				
				if (wayRing.getLastNode() == nextRing.getFirstNode()) {
					outerNodes.remove(outerNodes.size() - 1);
				} else {
					
					//insert a connection that doesn't cut through the bbox
					
					List<VectorXZ> connection = new ArrayList<VectorXZ>();
					
					MapNodeRing mapNodeRing = wayRing.getMapNodeRing();
					MapNode ringEndNode = mapNodeRing.get(mapNodeRing.size()-1);
					
					double ringAngle = center.angleTo(ringEndNode.getPos());
					double nextRingAngle = getRingAngle(center, nextRing);
					
					if (ringAngle < cornerAngle1 && (nextRingAngle > cornerAngle1
							|| nextRingAngle < ringAngle)) {
						
						connection.add(fileBoundary.topRight());
						
					}
					
					if (ringAngle < cornerAngle2 && (nextRingAngle > cornerAngle2
							|| nextRingAngle < ringAngle)) {
						
						connection.add(fileBoundary.bottomRight());
						
					}
					
					if (ringAngle < cornerAngle3 && (nextRingAngle > cornerAngle3
							|| nextRingAngle < ringAngle)) {
						
						connection.add(fileBoundary.bottomLeft());
						
					}
					
					if (ringAngle < cornerAngle4 && (nextRingAngle > cornerAngle4
							|| nextRingAngle < ringAngle)) {
						
						connection.add(fileBoundary.topLeft());
						
					}
					
					for (VectorXZ pos : connection) {
												
						OSMNode osmNode = new OSMNode(NaN, NaN,
								COASTLINE_NODE_TAGS, highestNodeId + 1);
						osmData.getNodes().add(osmNode);
						highestNodeId += 1;
						
						MapNode mapNode = new MapNode(pos, osmNode);
						outerNodes.add(mapNode);
						mapNodes.add(mapNode);
						nodeMap.put(osmNode, mapNode);
						
					}
					
				}
				
			}
			
			//close the loop
			outerNodes.add(outerNodes.get(0));
			
			/* build the result */
			
			OSMRelation relation = new OSMRelation(new MapBasedTagGroup(
					new Tag("type", "multipolygon"), new Tag("natural", "water")),
					highestRelationId + 1, coastlineWays.size());
			
			mapNodeRings.add(outerNodes);
			
			return buildPolygonsFromRings(relation, mapNodeRings);
			
		}
		
		return emptyList();
		
	}
	
	private static final double getRingAngle(VectorXZ center, WayRing wayRing) {
		return center.angleTo(wayRing.getMapNodeRing().get(0).getPos());
	}
	
	private static final class WayRing {
		
		private Map<OSMNode, MapNode> nodeMap;
		private List<RingSegment> ringSegments = new ArrayList<RingSegment>();
		
		public WayRing(OSMWay firstWay, Map<OSMNode, MapNode> nodeMap) {
			
			this.nodeMap = nodeMap;
			this.ringSegments.add(new RingSegment(firstWay, true));
			
		}
		
		private OSMNode getFirstNode() {
			
			return ringSegments.get(0).getStartNode();
			
		}
		
		private OSMNode getLastNode() {
			
			RingSegment lastSegment = ringSegments.get(ringSegments.size() - 1);
			return lastSegment.getEndNode();
			
		}
		
		/**
		 * tries to add a way either at the beginning or end of the ring
		 * @return true iff the way was successfully added
		 */
		public boolean tryAddWay(OSMWay way) {

			OSMNode firstWayNode = way.nodes.get(0);
			OSMNode lastWayNode = way.nodes.get(way.nodes.size() - 1);
			
			if (getLastNode() == firstWayNode) {
				
				//add the way at the end
				ringSegments.add(new RingSegment(way, true));
				return true;
				
			} else if (getLastNode() == lastWayNode) {
				
				//add the way backwards at the end
				ringSegments.add(new RingSegment(way, false));
				return true;
				
			} else if (getFirstNode() == lastWayNode) {

				//add the way at the beginning
				ringSegments.add(0, new RingSegment(way, true));
				return true;
				
			} else if (getFirstNode() == firstWayNode) {

				//add the way backwards at the beginning
				ringSegments.add(0, new RingSegment(way, false));
				return true;
				
			} else {
				
				return false;
			}
			
		}

		public boolean isClosed() {
			return getFirstNode() == getLastNode();
		}
		
		MapNodeRing mapNodes = null;
		
		public MapNodeRing getMapNodeRing() {
			
			if (mapNodes == null) {
				
				mapNodes = new MapNodeRing();
					
				for (RingSegment ringSegment : ringSegments) {
					
					int size = ringSegment.way.nodes.size();
					
					for (int i = 0; i < size; i++) {
						
						int nodeIndex = ringSegment.forward ? i : (size - 1 - i);
						OSMNode node = ringSegment.way.nodes.get(nodeIndex);
						
						if (i == 0 && !mapNodes.isEmpty()) {
							//node has already been added
							assert nodeMap.get(node) ==
									mapNodes.get(mapNodes.size() - 1);
						} else {
							mapNodes.add(nodeMap.get(node));
						}
						
					}
					
				}
				
			}
			
			return mapNodes;
			
		}
		
		@Override
		public String toString() {
			return ringSegments.toString();
		}
		
		/**
		 * one entry in a list of segments composing a ring.
		 * 
		 * This is intended to mask the direction of the underlying
		 * {@link OSMWay} from other methods.
		 */
		private static final class RingSegment {
			
			public final OSMWay way;
			public final boolean forward;
			
			private RingSegment(OSMWay way, boolean forward) {
				this.way = way;
				this.forward = forward;
			}
			
			public OSMNode getStartNode() {
				if (forward) {
					return way.nodes.get(0);
				} else {
					return way.nodes.get(way.nodes.size() - 1);
				}
			}
			
			public OSMNode getEndNode() {
				if (forward) {
					return way.nodes.get(way.nodes.size() - 1);
				} else {
					return way.nodes.get(0);
				}
			}
			
			@Override
			public String toString() {
				return "(" + way.id + (forward ? "f" : "b") + ")";
			}
			
		}
		
	}
	
	private static final class MapNodeRing extends ArrayList<MapNode> {
		
		private SimplePolygonXZ polygon = null;
		
		public SimplePolygonXZ getPolygon() {
			
			if (polygon == null) {
				polygon = MapArea.polygonFromMapNodeLoop(this);
			}
			
			return polygon;
			
		}
		
		public boolean contains(MapNodeRing other) {
			return this.getPolygon().contains(other.getPolygon());
		}
		
	}
		
}