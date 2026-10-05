package com.musibility.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final int LOCATION_REQUEST = 64;
    private final ExecutorService geocoderWorker = Executors.newSingleThreadExecutor();
    private GoogleMap map;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.rgb(1, 1, 1));
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("🎵  Bản đồ âm nhạc");
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(22);
        title.setPadding(20, 18, 20, 10);
        root.addView(title);
        int mapId = android.view.View.generateViewId();
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setId(mapId);
        root.addView(frame, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        SupportMapFragment fragment = SupportMapFragment.newInstance();
        getSupportFragmentManager().beginTransaction().replace(mapId, fragment).commit();
        fragment.getMapAsync(this);
    }

    @Override public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(true);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.setMyLocationEnabled(true);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_REQUEST);
        }
        LatLng last = null;
        for (JSONObject place : MusicData.places(this)) {
            double lat = place.optDouble("lat", 0);
            double lng = place.optDouble("lng", 0);
            if (lat == 0 && lng == 0) continue;
            last = new LatLng(lat, lng);
            String song = place.optString("title") + " - " + place.optString("artist");
            Marker marker = map.addMarker(new MarkerOptions().position(last).title(song)
                    .snippet("Chạm thông tin để tạo playlist tại đây"));
            if (marker != null) marker.setTag(place);
            map.addCircle(new CircleOptions().center(last).radius(120).strokeWidth(2)
                    .strokeColor(0xAAD7FA00).fillColor(0x33D7FA00));
        }
        map.setOnMarkerClickListener(marker -> {
            Object tag = marker.getTag();
            if (tag instanceof JSONObject && Geocoder.isPresent()) {
                JSONObject place = (JSONObject) tag;
                geocoderWorker.execute(() -> {
                    try {
                        List<Address> addresses = new Geocoder(this, Locale.getDefault())
                                .getFromLocation(place.optDouble("lat"), place.optDouble("lng"), 1);
                        String address = addresses == null || addresses.isEmpty()
                                ? "Vị trí đã nghe" : addresses.get(0).getAddressLine(0);
                        runOnUiThread(() -> {
                            marker.setTitle(address);
                            marker.showInfoWindow();
                        });
                    } catch (Exception e) {
                        android.util.Log.w("Musibility", "Could not resolve listening address", e);
                    }
                });
            }
            return false;
        });
        map.setOnInfoWindowClickListener(this::createLocationPlaylist);
        if (last != null) map.moveCamera(CameraUpdateFactory.newLatLngZoom(last, 12));
        else map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(21.0285, 105.8542), 5));
        if (BuildConfig.GOOGLE_MAPS_KEY.isEmpty()) {
            android.widget.Toast.makeText(this, "Thêm google.maps.key vào local.properties để bật Google Maps.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void createLocationPlaylist(Marker marker) {
        Object tag = marker.getTag();
        if (!(tag instanceof JSONObject)) return;
        JSONObject selected = (JSONObject) tag;
        double lat = selected.optDouble("lat");
        double lng = selected.optDouble("lng");
        List<Song> songs = new ArrayList<>();
        for (JSONObject place : MusicData.places(this)) {
            float[] distance = new float[1];
            android.location.Location.distanceBetween(lat, lng, place.optDouble("lat"),
                    place.optDouble("lng"), distance);
            if (distance[0] <= 200) {
                songs.add(new Song(place.optLong("id"), place.optString("title"),
                        place.optString("artist"), place.optLong("artistId"), place.optString("album"),
                        place.optString("cover"), place.optString("audio", place.optString("preview")),
                        place.optString("link"), place.optInt("duration"), place.optString("license"),
                        place.optString("provider", "Jamendo"), place.optString("providerTrackId"),
                        place.optString("artistSourceId", place.optString("artistId"))));
            }
        }
        String name = "Nghe tại " + marker.getTitle();
        try {
            MusicData.createLocationPlaylist(this, name, songs);
            android.widget.Toast.makeText(this, "Đã tạo playlist \"" + name + "\" với "
                    + songs.size() + " bài.", android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.util.Log.e("Musibility", "Could not create location playlist", e);
            android.widget.Toast.makeText(this, "Không thể tạo playlist tại vị trí này.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                       @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST && map != null && grantResults.length > 0
                && (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            try { map.setMyLocationEnabled(true); } catch (SecurityException e) {
                android.util.Log.w("Musibility", "Location permission was revoked", e);
            }
        }
    }

    @Override protected void onDestroy() {
        geocoderWorker.shutdown();
        super.onDestroy();
    }
}
