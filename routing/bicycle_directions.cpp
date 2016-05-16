#include "routing/bicycle_directions.hpp"
#include "routing/car_model.hpp"
#include "routing/router_delegate.hpp"
#include "routing/routing_result_graph.hpp"
#include "routing/turns_generator.hpp"

#include "indexer/ftypes_matcher.hpp"
#include "indexer/index.hpp"
#include "indexer/scales.hpp"

#include "geometry/point2d.hpp"

namespace
{
using namespace routing;
using namespace routing::turns;

class AStarRoutingResult : public IRoutingResult
{
public:
  AStarRoutingResult(IRoadGraph::TEdgeVector const & routeEdges,
                     AdjacentEdgesMap const & adjacentEdges,
                     TUnpackedPathSegments const & pathSegments)
    : m_routeEdges(routeEdges)
    , m_adjacentEdges(adjacentEdges)
    , m_pathSegments(pathSegments)
    , m_routeLength(0)
  {
    for (auto const & edge : routeEdges)
    {
      m_routeLength += MercatorBounds::DistanceOnEarth(edge.GetStartJunction().GetPoint(),
                                                       edge.GetEndJunction().GetPoint());
    }
  }

  // turns::IRoutingResult overrides:
  virtual TUnpackedPathSegments const & GetSegments() const override { return m_pathSegments; }

  virtual void GetPossibleTurns(TNodeId node, m2::PointD const & ingoingPoint,
                                m2::PointD const & junctionPoint, size_t & ingoingCount,
                                TurnCandidates & outgoingTurns) const override
  {
    ingoingCount = 0;
    outgoingTurns.candidates.clear();

    auto const adjacentEdges = m_adjacentEdges.find(node);
    if (adjacentEdges == m_adjacentEdges.cend())
    {
      ASSERT(false, ());
      return;
    }

    ingoingCount = adjacentEdges->second.m_ingoingTurnsCount;
    outgoingTurns.candidates = adjacentEdges->second.m_outgoingTurns.candidates;
  }

  virtual double GetPathLength() const override { return m_routeLength; }

  virtual m2::PointD const & GetStartPoint() const override
  {
    CHECK(!m_routeEdges.empty(), ());
    return m_routeEdges.front().GetStartJunction().GetPoint();
  }

  virtual m2::PointD const & GetEndPoint() const override
  {
    CHECK(!m_routeEdges.empty(), ());
    return m_routeEdges.back().GetEndJunction().GetPoint();
  }

private:
  IRoadGraph::TEdgeVector const & m_routeEdges;
  AdjacentEdgesMap const & m_adjacentEdges;
  TUnpackedPathSegments const & m_pathSegments;
  double m_routeLength;
};
}  // namespace

namespace routing
{
BicycleDirectionsEngine::BicycleDirectionsEngine(Index const & index) : m_index(index) {}

void BicycleDirectionsEngine::Generate(IRoadGraph const & graph, vector<Junction> const & path,
                                       Route::TTimes & times, Route::TTurns & turns,
                                       vector<m2::PointD> & routeGeometry,
                                       my::Cancellable const & cancellable)
{
  size_t const pathSize = path.size();
  CHECK_NOT_EQUAL(pathSize, 0, ());

  times.clear();
  turns.clear();
  routeGeometry.clear();
  m_adjacentEdges.clear();
  m_pathSegments.clear();

  auto emptyPathWorkaround = [&]()
  {
    turns.emplace_back(pathSize - 1, turns::TurnDirection::ReachedYourDestination);
    this->m_adjacentEdges[0] = AdjacentEdges(1);  // There's one ingoing edge to the finish.
  };

  if (pathSize <= 1)
  {
    ASSERT(false, (pathSize));
    emptyPathWorkaround();
    return;
  }

  CalculateTimes(graph, path, times);

  IRoadGraph::TEdgeVector routeEdges;
  if (!ReconstructPath(graph, path, routeEdges, cancellable))
  {
    LOG(LDEBUG, ("Couldn't reconstruct path"));
    emptyPathWorkaround();
    return;
  }
  if (routeEdges.empty())
  {
    ASSERT(false, ());
    emptyPathWorkaround();
    return;
  }

  // Filling |m_adjacentEdges|.
  m_adjacentEdges.insert(make_pair(0, AdjacentEdges(0)));
  for (size_t i = 1; i < pathSize; ++i)
  {
    Junction const & prevJunction = path[i - 1];
    Junction const & currJunction = path[i];
    IRoadGraph::TEdgeVector outgoingEdges, ingoingEdges;
    graph.GetOutgoingEdges(currJunction, outgoingEdges);
    graph.GetIngoingEdges(currJunction, ingoingEdges);

    AdjacentEdges adjacentEdges = AdjacentEdges(ingoingEdges.size());
    // Outgoing edge angle is not used for bicyle routing.
    adjacentEdges.m_outgoingTurns.isCandidatesAngleValid = false;
    adjacentEdges.m_outgoingTurns.candidates.reserve(outgoingEdges.size());
    ASSERT_EQUAL(routeEdges.size(), pathSize - 1, ());
    FeatureID const inEdgeFeatureId = routeEdges[i - 1].GetFeatureId();

    for (auto const & edge : outgoingEdges)
    {
      auto const & outFeatureId = edge.GetFeatureId();
      // Checking for if |edge| is a fake edge.
      if (!outFeatureId.IsValid())
        continue;
      adjacentEdges.m_outgoingTurns.candidates.emplace_back(0. /* angle */, outFeatureId.m_index,
                                                            GetHighwayClass(outFeatureId));
    }

    LoadedPathSegment pathSegment;
    if (inEdgeFeatureId.IsValid())
      LoadPathGeometry(inEdgeFeatureId, {prevJunction.GetPoint(), currJunction.GetPoint()}, pathSegment);

    m_adjacentEdges.insert(make_pair(inEdgeFeatureId.m_index, move(adjacentEdges)));
    m_pathSegments.push_back(move(pathSegment));
  }

  AStarRoutingResult resultGraph(routeEdges, m_adjacentEdges, m_pathSegments);
  RouterDelegate delegate;
  Route::TTimes turnAnnotationTimes;
  Route::TStreets streetNames;
  MakeTurnAnnotation(resultGraph, delegate, routeGeometry, turns, turnAnnotationTimes, streetNames);
}

void BicycleDirectionsEngine::UpdateFeatureLoaderGuardIfNeeded(Index const & index, MwmSet::MwmId const & mwmId)
{
  if (!m_featuresLoaderGuard || mwmId != m_mwmIdFeaturesLoaderGuard)
    m_featuresLoaderGuard.reset(new Index::FeaturesLoaderGuard(index, mwmId));
}

ftypes::HighwayClass BicycleDirectionsEngine::GetHighwayClass(FeatureID const & featureId)
{
  ftypes::HighwayClass highWayClass = ftypes::HighwayClass::Undefined;
  MwmSet::MwmId const & mwmId = featureId.m_mwmId;
  uint32_t const featureIndex = featureId.m_index;

  FeatureType ft;
  UpdateFeatureLoaderGuardIfNeeded(m_index, mwmId);
  m_featuresLoaderGuard->GetFeatureByIndex(featureIndex, ft);
  highWayClass = ftypes::GetHighwayClass(ft);
  ASSERT_NOT_EQUAL(highWayClass, ftypes::HighwayClass::Error, ());
  ASSERT_NOT_EQUAL(highWayClass, ftypes::HighwayClass::Undefined, ());
  return highWayClass;
}

void BicycleDirectionsEngine::LoadPathGeometry(FeatureID const & featureId, vector<m2::PointD> const & path,
                                               LoadedPathSegment & pathSegment)
{
  pathSegment.Clear();

  MwmSet::MwmId const & mwmId = featureId.m_mwmId;
  if (!featureId.IsValid())
  {
    ASSERT(false, ());
    return;
  }

  FeatureType ft;
  UpdateFeatureLoaderGuardIfNeeded(m_index, mwmId);
  m_featuresLoaderGuard->GetFeatureByIndex(featureId.m_index, ft);
  pathSegment.m_highwayClass =  ftypes::GetHighwayClass(ft);
  ASSERT_NOT_EQUAL(pathSegment.m_highwayClass, ftypes::HighwayClass::Error, ());
  ASSERT_NOT_EQUAL(pathSegment.m_highwayClass, ftypes::HighwayClass::Undefined, ());
  pathSegment.m_isLink = ftypes::IsLinkChecker::Instance()(ft);

  ft.GetName(FeatureType::DEFAULT_LANG, pathSegment.m_name);

  pathSegment.m_nodeId = featureId.m_index;
  pathSegment.m_onRoundabout = ftypes::IsRoundAboutChecker::Instance()(ft);
  pathSegment.m_path = path;
  // @TODO(bykoianko) It's better to fill pathSegment.m_weight.
}
}  // namespace routing
