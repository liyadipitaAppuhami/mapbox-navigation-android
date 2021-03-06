package com.mapbox.services.android.navigation.v5.navigation;

import android.location.Location;

import com.mapbox.services.android.navigation.v5.milestone.Milestone;
import com.mapbox.services.android.navigation.v5.offroute.OffRoute;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.navigation.v5.snap.Snap;
import com.mapbox.services.android.telemetry.utils.MathUtils;
import com.mapbox.services.api.directions.v5.models.DirectionsRoute;
import com.mapbox.services.api.directions.v5.models.LegStep;
import com.mapbox.services.api.utils.turf.TurfConstants;
import com.mapbox.services.api.utils.turf.TurfMeasurement;
import com.mapbox.services.api.utils.turf.TurfMisc;
import com.mapbox.services.commons.geojson.Feature;
import com.mapbox.services.commons.geojson.LineString;
import com.mapbox.services.commons.geojson.Point;
import com.mapbox.services.commons.models.Position;

import java.util.ArrayList;
import java.util.List;

import static com.mapbox.services.Constants.PRECISION_6;

/**
 * This contains several single purpose methods that help out when a new location update occurs and
 * calculations need to be performed on it.
 */
class NavigationHelper {

  /**
   * Takes in a raw location, converts it to a point, and snaps it to the closest point along the
   * route. This is isolated as separate logic from the snap logic provided because we will always
   * need to snap to the route in order to get the most accurate information.
   */
  static Position userSnappedToRoutePosition(Location location, List<Position> coordinates) {
    Point locationToPoint = Point.fromCoordinates(
      new double[] {location.getLongitude(), location.getLatitude()}
    );


    // Uses Turf's pointOnLine, which takes a Point and a LineString to calculate the closest
    // Point on the LineString.
    Feature feature = TurfMisc.pointOnLine(locationToPoint, coordinates);
    return ((Point) feature.getGeometry()).getCoordinates();
  }

  /**
   * When a milestones triggered, it's instruction needs to be built either using the provided
   * string or an empty string.
   */
  static String buildInstructionString(RouteProgress routeProgress, Milestone milestone) {
    if (milestone.getInstruction() != null) {
      // Create a new custom instruction based on the Instruction packaged with the Milestone
      return milestone.getInstruction().buildInstruction(routeProgress);
    }
    return "";
  }

  /**
   * Calculates the distance remaining in the step from the current users snapped position, to the
   * next maneuver position.
   */
  static double stepDistanceRemaining(Position snappedPosition, int legIndex, int stepIndex,
                                      DirectionsRoute directionsRoute, List<Position> coordinates) {
    List<LegStep> steps = directionsRoute.getLegs().get(legIndex).getSteps();
    Position nextManeuverPosition = nextManeuverPosition(stepIndex, steps, coordinates);

    LineString lineString = LineString.fromPolyline(steps.get(stepIndex).getGeometry(), PRECISION_6);
    // If the users snapped position equals the next maneuver
    // position or the linestring coordinate size is less than 2,the distance remaining is zero.
    if (snappedPosition.equals(nextManeuverPosition) || lineString.getCoordinates().size() < 2) {
      return 0;
    }
    LineString slicedLine = TurfMisc.lineSlice(Point.fromCoordinates(snappedPosition),
      Point.fromCoordinates(nextManeuverPosition), lineString);
    return TurfMeasurement.lineDistance(slicedLine, TurfConstants.UNIT_METERS);
  }

  /**
   * Takes in the already calculated step distance and iterates through the step list from the
   * step index value plus one till the end of the leg.
   */
  static double legDistanceRemaining(double stepDistanceRemaining, int legIndex, int stepIndex,
                                     DirectionsRoute directionsRoute) {
    List<LegStep> steps = directionsRoute.getLegs().get(legIndex).getSteps();
    if ((steps.size() < stepIndex + 1)) {
      return stepDistanceRemaining;
    }
    for (int i = stepIndex + 1; i < steps.size(); i++) {
      stepDistanceRemaining += steps.get(i).getDistance();
    }
    return stepDistanceRemaining;
  }

  /**
   * Takes in the leg distance remaining value already calculated and if additional legs need to be
   * traversed along after the current one, adds those distances and returns the new distance.
   * Otherwise, if the route only contains one leg or the users on the last leg, this value will
   * equal the leg distance remaining.
   */
  static double routeDistanceRemaining(double legDistanceRemaining, int legIndex,
                                       DirectionsRoute directionsRoute) {
    if (directionsRoute.getLegs().size() < 2) {
      return legDistanceRemaining;
    }

    for (int i = legIndex + 1; i < directionsRoute.getLegs().size(); i++) {
      legDistanceRemaining += directionsRoute.getLegs().get(i).getDistance();
    }
    return legDistanceRemaining;
  }

  /**
   * Checks whether the user's bearing matches the next step's maneuver provided bearingAfter
   * variable. This is one of the criteria's required for the user location to be recognized as
   * being on the next step or potentially arriving.
   *
   * @param userLocation  the location of the user
   * @param routeProgress used for getting route information
   * @return boolean true if the user location matches (using a tolerance) the final heading
   * @since 0.2.0
   */
  static boolean bearingMatchesManeuverFinalHeading(Location userLocation, RouteProgress routeProgress,
                                                    double maxTurnCompletionOffset) {
    if (routeProgress.currentLegProgress().upComingStep() == null) {
      return false;
    }

    // Bearings need to be normalized so when the bearingAfter is 359 and the user heading is 1, we
    // count this as within the MAXIMUM_ALLOWED_DEGREE_OFFSET_FOR_TURN_COMPLETION.
    double finalHeading = routeProgress.currentLegProgress().upComingStep().getManeuver().getBearingAfter();
    double finalHeadingNormalized = MathUtils.wrap(finalHeading, 0, 360);
    double userHeadingNormalized = MathUtils.wrap(userLocation.getBearing(), 0, 360);
    return MathUtils.differenceBetweenAngles(finalHeadingNormalized, userHeadingNormalized)
      <= maxTurnCompletionOffset;
  }

  /**
   * This is used when a user has completed a step maneuver and the indices need to be incremented.
   * The main purpose of this class is to determine if an additional leg exist and the step index
   * has met the first legs total size, a leg index needs to occur and step index should be reset.
   * Otherwise, the step index is incremented while the leg index remains the same.
   * <p>
   * Rather than returning an int array, a new instance of Navigation Indices gets returned. This
   * provides type safety and making the code a bit more readable.
   * </p>
   *
   * @param routeProgress   need a routeProgress in order to get the directions route leg list size
   * @param previousIndices used for adjusting the indices
   * @return a {@link NavigationIndices} object which contains the new leg and step indices
   */
  static NavigationIndices increaseIndex(RouteProgress routeProgress, NavigationIndices previousIndices) {
    // Check if we are in the last step in the current routeLeg and iterate it if needed.
    if (previousIndices.stepIndex()
      >= routeProgress.directionsRoute().getLegs().get(routeProgress.legIndex()).getSteps().size() - 2
      && previousIndices.legIndex() < routeProgress.directionsRoute().getLegs().size() - 1) {
      return NavigationIndices.create((previousIndices.legIndex() + 1), 0);
    }
    return NavigationIndices.create(previousIndices.legIndex(), (previousIndices.stepIndex() + 1));
  }

  static List<Milestone> checkMilestones(RouteProgress previousRouteProgress,
                                         RouteProgress routeProgress, MapboxNavigation mapboxNavigation) {
    List<Milestone> milestones = new ArrayList<>();
    for (Milestone milestone : mapboxNavigation.getMilestones()) {
      if (milestone.isOccurring(previousRouteProgress, routeProgress)) {
        milestones.add(milestone);
      }
    }
    return milestones;
  }

  static boolean isUserOffRoute(NewLocationModel newLocationModel, RouteProgress routeProgress) {
    OffRoute offRoute = newLocationModel.mapboxNavigation().getOffRouteEngine();
    return offRoute.isUserOffRoute(newLocationModel.location(), routeProgress,
      newLocationModel.mapboxNavigation().options());
  }

  static Location getSnappedLocation(MapboxNavigation mapboxNavigation, Location location,
                                     RouteProgress routeProgress, List<Position> stepCoordinates) {
    Snap snap = mapboxNavigation.getSnapEngine();
    return snap.getSnappedLocation(location, routeProgress, stepCoordinates);
  }

  /**
   * Retrieves the next steps maneuver position if one exist, otherwise it decodes the current steps
   * geometry and uses the last coordinate in the position list.
   */
  static Position nextManeuverPosition(int stepIndex, List<LegStep> steps, List<Position> coords) {
    // If there is an upcoming step, use it's maneuver as the position.
    if (steps.size() > (stepIndex + 1)) {
      return steps.get(stepIndex + 1).getManeuver().asPosition();
    }
    return coords.size() >= 1 ? coords.get(coords.size() - 1) : coords.get(coords.size());
  }
}
