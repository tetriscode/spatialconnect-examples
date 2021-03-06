package com.boundlessgeo.spatialconnect.app;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.boundlessgeo.spatialconnect.geometries.SCBoundingBox;
import com.boundlessgeo.spatialconnect.geometries.SCGeometry;
import com.boundlessgeo.spatialconnect.geometries.SCSpatialFeature;
import com.boundlessgeo.spatialconnect.query.SCGeometryPredicateComparison;
import com.boundlessgeo.spatialconnect.query.SCPredicate;
import com.boundlessgeo.spatialconnect.query.SCQueryFilter;
import com.boundlessgeo.spatialconnect.scutilities.GoogleMapsUtil;
import com.boundlessgeo.spatialconnect.scutilities.LocationHelper;
import com.boundlessgeo.spatialconnect.services.SCDataService;
import com.boundlessgeo.spatialconnect.stores.GeoPackageStore;
import com.boundlessgeo.spatialconnect.stores.SCDataStore;
import com.boundlessgeo.spatialconnect.stores.SCKeyTuple;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;


public class MapsFragment extends Fragment implements OnMapReadyCallback {

    private GoogleMap map; // Might be null if Google Play services APK is not available.
    private Activity mainActivity;
    private MapFragment mapFragment;
    private SCDataService dataService;

    HashMap <String, SCKeyTuple> mMarkers = new HashMap<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mainActivity.getFragmentManager().beginTransaction().add(R.id.container, mapFragment).commit();
        dataService = SpatialConnectService.getInstance().getServiceManager(getContext()).getDataService();
        setUpMapIfNeeded();
    }

    @Override
    public void onAttach(Context activity) {
        super.onAttach(activity);
        mainActivity = getActivity();
        mapFragment = MapFragment.newInstance();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (map != null) {
            reloadFeatures();
        }
    }

    /**
     * Sets up the map if it is possible to do so (i.e., the Google Play services APK is correctly
     * installed) and the map has not already been instantiated.. This will ensure that we only
     * ever call {@link #setUpMap()} once when {@link #map} is not null.
     */
    protected void setUpMapIfNeeded() {
        if (map == null) {
            mapFragment.getMapAsync(this);
        }
    }

    protected void setUpMap() {
//        map.setMyLocationEnabled(true);
//        map.getUiSettings().setMyLocationButtonEnabled(true);
//        map.setOnMyLocationButtonClickListener(new LocationHelper(mainActivity, map));
        map.getUiSettings().setZoomControlsEnabled(true);
        map.setOnInfoWindowClickListener(new GoogleMap.OnInfoWindowClickListener() {
            @Override
            public void onInfoWindowClick(Marker marker) {
                Intent intent = new Intent(MapsFragment.this.getActivity(),FeatureDetails.class);
                intent.putExtra("lat", marker.getPosition().latitude);
                intent.putExtra("lon",marker.getPosition().longitude);
                SCKeyTuple kt = mMarkers.get(marker.getId());
                intent.putExtra("sid",kt.getStoreId());
                intent.putExtra("lid",kt.getLayerId());
                intent.putExtra("fid",kt.getFeatureId());
                mMarkers.get(marker.getId());
                startActivity(intent);
            }
        });
    }


    private void loadFeatures(SCBoundingBox bbox) {
        // apply predicate to filter
        SCQueryFilter filter = new SCQueryFilter(
                new SCPredicate(bbox, SCGeometryPredicateComparison.SCPREDICATE_OPERATOR_WITHIN)
        );

        SCDataStore ds = dataService.getActiveStores().get(1);
        ds.query(filter)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .take(10) // this should be configurable or a default
                .subscribe(
                        new Subscriber<SCSpatialFeature>() {
                            int num_features = 0;
                            SCSpatialFeature latestFeature;

                            @Override
                            public void onStart() {
                                request(10);
                            }

                            @Override
                            public void onCompleted() {
                                if (latestFeature != null) {
                                    LatLng northeast = new LatLng(
                                            ((SCGeometry) latestFeature).getGeometry().getEnvelopeInternal().getMaxY(),
                                            ((SCGeometry) latestFeature).getGeometry().getEnvelopeInternal().getMaxX()
                                    );
                                    LatLng southwest = new LatLng(
                                            ((SCGeometry) latestFeature).getGeometry().getEnvelopeInternal().getMinY(),
                                            ((SCGeometry) latestFeature).getGeometry().getEnvelopeInternal().getMinX()
                                    );
                                    final Toast toast = Toast.makeText(
                                            mainActivity,
                                            num_features + " features loaded",
                                            Toast.LENGTH_SHORT
                                    );
                                    Handler handler = new Handler();
                                    handler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast.cancel();
                                        }
                                    }, 300);
                                    toast.show();
                                }
                            }

                            @Override
                            public void onError(Throwable e) {
                                e.printStackTrace();
                                Log.e("MapsFragment.Subscriber", "onError()\n" + e.getLocalizedMessage());
                            }

                            @Override
                            public void onNext(SCSpatialFeature feature) {
                                if (feature instanceof SCGeometry && ((SCGeometry) feature).getGeometry() != null) {
                                    latestFeature = feature;
                                    Marker m = GoogleMapsUtil.addPointToMap(map, (SCGeometry) feature);
                                    MapsFragment.this.mMarkers.put(m.getId(),feature.getKey());
                                }
                                request(10);
                            }
                        }
                );
    }

    private void setGeoPackageTileProvider() {
        SCDataStore selectedStore = dataService.getActiveStores().get(0);
        GeoPackageStore geoPackageStore = (GeoPackageStore) SpatialConnectService.getInstance().getServiceManager(getContext())
                .getDataService()
                .getStoreById(selectedStore.getStoreId());

        geoPackageStore.addGeoPackageTileOverlay(
                map,
                selectedStore.getAdapter().getDataStoreName(),
                "WhiteHorse"
        );
        CameraUpdate cu = CameraUpdateFactory.newLatLngZoom(new LatLng(60.86, -135.19), 14);
        map.animateCamera(cu);
    }

    private SCBoundingBox getCurrentBoundingBox() {
        LatLng ne = this.map.getProjection().getVisibleRegion().latLngBounds.northeast;
        LatLng sw = this.map.getProjection().getVisibleRegion().latLngBounds.southwest;

        SCBoundingBox bbox = new SCBoundingBox(sw.longitude,sw.latitude,ne.longitude,ne.latitude);
        return bbox;
    }

    public void reloadFeatures() {
        loadFeatures(getCurrentBoundingBox());
    }

    @Override
    public void onMapReady(GoogleMap map) {
        this.map = map;

        onCameraChangeObs().debounce(500,TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Subscriber<CameraPosition>() {
                    @Override
                    public void onCompleted() {
                        Log.e("MAP", "Error: Shoudln't have unsubscribed from MapChangeObs");
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e("MAP", e.getLocalizedMessage());
                    }

                    @Override
                    public void onNext(CameraPosition position) {
                        Log.i("MAP", "OnNext Camera Changed");
                        reloadFeatures();
                    }
                });

        setUpMap();
        reloadFeatures();
    }

    public Observable<CameraPosition> onCameraChangeObs() {
        return Observable.create(new Observable.OnSubscribe<CameraPosition>() {

            @Override
            public void call(final Subscriber<? super CameraPosition> subscriber) {
                MapsFragment.this.map.setOnCameraChangeListener(
                        new GoogleMap.OnCameraChangeListener() {
                            @Override
                            public void onCameraChange(com.google.android.gms.maps.model.CameraPosition cameraPosition) {
                                subscriber.onNext(cameraPosition);

                            }
                        }
                );
            }
        });
    }

}
