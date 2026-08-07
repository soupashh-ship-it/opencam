package io.opencam.webcam;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Minimal framework-only settings screen. Every control persists immediately via {@link Prefs}.
 */
public class SettingsActivity extends Activity {

    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        rows = findViewById(R.id.rows);

        addTextRow(getString(R.string.pref_port), String.valueOf(Prefs.port(this)),
                true, new TextSaver() {
                    @Override
                    public void save(String value) {
                        try {
                            Prefs.putInt(SettingsActivity.this, Prefs.PORT, Integer.parseInt(value));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                });

        addTextRow(getString(R.string.pref_device_name), Prefs.deviceName(this),
                false, new TextSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putString(SettingsActivity.this, Prefs.DEVICE_NAME, value);
                    }
                });

        addSpinnerRow(getString(R.string.pref_codec),
                R.array.codec_entries, R.array.codec_values,
                Prefs.codec(this), new SpinnerSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putString(SettingsActivity.this, Prefs.CODEC, value);
                    }
                });

        addSpinnerRow(getString(R.string.pref_resolution),
                R.array.resolution_entries, R.array.resolution_values,
                Prefs.resolution(this), new SpinnerSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putString(SettingsActivity.this, Prefs.RESOLUTION, value);
                    }
                });

        addSpinnerRow(getString(R.string.pref_fps),
                R.array.fps_entries, R.array.fps_values,
                String.valueOf(Prefs.fps(this)), new SpinnerSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putInt(SettingsActivity.this, Prefs.FPS, Integer.parseInt(value));
                    }
                });

        addSpinnerRow(getString(R.string.pref_bitrate),
                R.array.bitrate_entries, R.array.bitrate_values,
                String.valueOf(Prefs.bitrateKbps(this)), new SpinnerSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putInt(SettingsActivity.this, Prefs.BITRATE, Integer.parseInt(value));
                    }
                });

        addSpinnerRow(getString(R.string.pref_jpeg_quality),
                R.array.jpeg_quality_entries, R.array.jpeg_quality_values,
                String.valueOf(Prefs.jpegQuality(this)), new SpinnerSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putInt(SettingsActivity.this, Prefs.JPEG_QUALITY, Integer.parseInt(value));
                    }
                });

        addCheckRow(getString(R.string.pref_audio), Prefs.audioEnabled(this), new CheckSaver() {
            @Override
            public void save(boolean value) {
                Prefs.putBoolean(SettingsActivity.this, Prefs.AUDIO_ENABLED, value);
            }
        });

        addSpinnerRow(getString(R.string.pref_audio_rate),
                R.array.sample_rate_entries, R.array.sample_rate_values,
                String.valueOf(Prefs.audioSampleRate(this)), new SpinnerSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putInt(SettingsActivity.this, Prefs.AUDIO_SAMPLE_RATE, Integer.parseInt(value));
                    }
                });

        addSpinnerRow(getString(R.string.pref_audio_bitrate),
                R.array.audio_bitrate_entries, R.array.audio_bitrate_values,
                String.valueOf(Prefs.audioBitrateKbps(this)), new SpinnerSaver() {
                    @Override
                    public void save(String value) {
                        Prefs.putInt(SettingsActivity.this, Prefs.AUDIO_BITRATE, Integer.parseInt(value));
                    }
                });

        addCheckRow(getString(R.string.pref_nsd), Prefs.nsdEnabled(this), new CheckSaver() {
            @Override
            public void save(boolean value) {
                Prefs.putBoolean(SettingsActivity.this, Prefs.NSD_ENABLED, value);
            }
        });
    }

    private interface TextSaver {
        void save(String value);
    }

    private interface SpinnerSaver {
        void save(String value);
    }

    private interface CheckSaver {
        void save(boolean value);
    }

    private void addTextRow(String title, String initial, boolean numeric, final TextSaver saver) {
        TextView label = label(title);
        final EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(initial);
        if (numeric) {
            edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        edit.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        edit.setLayoutParams(lp);
        edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    saver.save(edit.getText().toString());
                }
            }
        });
        addRow(label, edit);
    }

    private void addSpinnerRow(String title, int entriesRes, int valuesRes,
                               String current, final SpinnerSaver saver) {
        TextView label = label(title);
        final Spinner spinner = new Spinner(this);
        String[] entries = getResources().getStringArray(entriesRes);
        final String[] values = getResources().getStringArray(valuesRes);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, entries);
        spinner.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                spinner.setSelection(i);
                break;
            }
        }
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) {
                    first = false;
                    return;
                }
                saver.save(values[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        addRow(label, spinner);
    }

    private void addCheckRow(String title, boolean checked, final CheckSaver saver) {
        TextView label = label(title);
        final CheckBox check = new CheckBox(this);
        check.setChecked(checked);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saver.save(check.isChecked());
            }
        });
        addRow(label, check);
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getResources().getColor(R.color.text_primary));
        label.setTextSize(15f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private void addRow(View label, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelLp);
        row.addView(label);
        row.addView(control);
        rows.addView(row);
    }
}
