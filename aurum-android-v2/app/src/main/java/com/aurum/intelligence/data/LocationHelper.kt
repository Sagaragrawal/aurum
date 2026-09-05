package com.aurum.intelligence.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocationDetails(
    val pincode: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val source: String = "default", // "gps", "saved", "manual", "default"
)

object LocationHelper {
    const val DEFAULT_PINCODE = "560048"

    fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    suspend fun resolveLocationDetails(
        context: Context,
        userPincode: String?,
        savedPincode: String?,
        savedAddress: String? = null,
    ): LocationDetails {
        // Priority 1: User Explicit Input
        if (!userPincode.isNullOrBlank() && userPincode.matches(Regex("\\d{6}"))) {
            return LocationDetails(
                pincode = userPincode,
                address = savedAddress,
                source = "manual",
            )
        }

        // Priority 2: Saved Preference Pincode
        if (!savedPincode.isNullOrBlank() && savedPincode.matches(Regex("\\d{6}"))) {
            return LocationDetails(
                pincode = savedPincode,
                address = savedAddress,
                source = "saved",
            )
        }

        // Priority 3: Try GPS Geocoder if permission is available
        if (hasLocationPermission(context)) {
            val gpsLocation = detectGpsLocationDetails(context)
            if (gpsLocation != null && gpsLocation.pincode.matches(Regex("\\d{6}"))) {
                return gpsLocation
            }
        }

        // Priority 4: Default Fallback
        return LocationDetails(pincode = DEFAULT_PINCODE, source = "default")
    }

    suspend fun detectGpsPincode(context: Context): String? = detectGpsLocationDetails(context)?.pincode

    suspend fun detectGpsLocationDetails(context: Context): LocationDetails? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission(context)) return@withContext null
        return@withContext runCatching {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@runCatching null
            val location: Location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return@runCatching null

            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            val address = addresses?.firstOrNull()
            val postalCode = address?.postalCode?.takeIf { it.matches(Regex("\\d{6}")) }

            if (postalCode != null) {
                val formattedAddress = listOfNotNull(address.locality, address.subAdminArea, address.adminArea)
                    .joinToString(", ")
                LocationDetails(
                    pincode = postalCode,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    address = formattedAddress,
                    source = "gps",
                )
            } else null
        }.getOrNull()
    }

    fun buildPincodeInjectionScript(pincode: String, latitude: Double? = null, longitude: Double? = null): String {
        val lat = latitude ?: 12.9716
        val lng = longitude ?: 77.5946
        return """
        (function() {
            try {
                // Mock HTML5 Geolocation API for precise location
                const mockGeolocation = {
                    getCurrentPosition: function(success) {
                        success({
                            coords: { latitude: $lat, longitude: $lng, accuracy: 10, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
                            timestamp: Date.now()
                        });
                    },
                    watchPosition: function(success) {
                        success({
                            coords: { latitude: $lat, longitude: $lng, accuracy: 10 },
                            timestamp: Date.now()
                        });
                        return 1;
                    },
                    clearWatch: function() {}
                };
                Object.defineProperty(navigator, 'geolocation', { value: mockGeolocation, configurable: true });

                // Set cookies for all major retailers
                document.cookie = "pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                document.cookie = "ajio_pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                document.cookie = "mynt-ulc=pincode:$pincode; path=/; Secure; SameSite=Lax";
                document.cookie = "fk_pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                document.cookie = "location_lat=$lat; path=/; max-age=31536000; SameSite=Lax";
                document.cookie = "location_lng=$lng; path=/; max-age=31536000; SameSite=Lax";
                
                // Set localStorage values
                localStorage.setItem('pincode', '$pincode');
                localStorage.setItem('userPincode', '$pincode');
                localStorage.setItem('deliveryPincode', '$pincode');
                localStorage.setItem('fk_pincode', '$pincode');
                localStorage.setItem('userLocation', JSON.stringify({lat: $lat, lng: $lng, pincode: '$pincode'}));
                
                // Auto-close modal dialogs and dismiss location popups/tooltips
                document.querySelectorAll('.ic-close, [data-testid="close-button"], .close-button, .modal-close, button.close, #pge-close-x, .pincode-modal-close, .delivery-location-tooltip, .tooltip-ok, .popover-ok').forEach(function(el) {
                    try { el.click(); } catch (_) {}
                });

                // Auto-click "OK" or "Got It" or "Allow" location buttons in popovers
                Array.from(document.querySelectorAll('button, div[role="button"], span')).forEach(function(el) {
                    const txt = (el.textContent || '').trim().toUpperCase();
                    if (txt === 'OK' || txt === 'GOT IT' || txt === 'ALLOW' || txt === 'AGREE') {
                        if (el.offsetWidth > 0 && el.offsetHeight > 0) {
                            try { el.click(); } catch (_) {}
                        }
                    }
                });
            } catch (_) {}
        })();
    """.trimIndent()
    }

    fun buildPincodeHeaders(pincode: String): Map<String, String> = mapOf(
        "X-Pincode" to pincode,
        "X-Delivery-Pincode" to pincode,
        "Cookie" to "pincode=$pincode; ajio_pincode=$pincode; mynt-ulc=pincode:$pincode; fk_pincode=$pincode",
    )
}
