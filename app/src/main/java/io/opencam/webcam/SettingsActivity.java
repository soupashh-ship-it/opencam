package io.opencam.webcam;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Framework-only settings screen with a modern look: settings are grouped into
 * sections, each row is a rounded card with a styled input/spinner/switch that
 * persists immediately via {@link Prefs}.
 */
public class SettingsActivity extends Activity {

    private LinearLayout sections;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        sections = findViewById(R.id.sections);

        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        addSectionHeader(getString(R.string.section_streaming));
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

        addSectionHeader(getString(R.string.section_audio));
        addSwitchRow(getString(R.string.pref_audio), Prefs.audioEnabled(this), new CheckSaver() {
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

        addSectionHeader(getString(R.string.section_network));
        addSwitchRow(getString(R.string.pref_nsd), Prefs.nsdEnabled(this), new CheckSaver() {
            @Override
            public void save(boolean value) {
                Prefs.putBoolean(SettingsActivity.this, Prefs.NSD_ENABLED, value);
            }
        });
    }

    // ---- savers ------------------------------------------------------------

    private interface TextSaver {
        void save(String value);
    }

    private interface SpinnerSaver {
        void save(String value);
    }

    private interface CheckSaver {
        void save(boolean value);
    }

    // ---- row builders --------------------------------------------------------

    private void addSectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(getResources().getColor(R.color.accent));
        header.setTextSize(12f);
        header.setLetterSpacing(0.08f);
        header.setPadding(dp(2), dp(6), 0, dp(8));
        sections.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_row);
        card.setPadding(dp(16), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView rowLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getResources().getColor(R.color.text_primary));
        label.setTextSize(14f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(lp);
        return label;
    }

    private void addTextRow(String title, String initial, boolean numeric, final TextSaver saver) {
        LinearLayout card = card();
        card.addView(rowLabel(title));
        final EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(initial);
        if (numeric) {
            edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        edit.setSelectAllOnFocus(true);
        edit.setTextColor(getResources().getColor(R.color.text_primary));
        edit.setTextSize(14f);
        edit.setBackgroundResource(R.drawable.bg_input);
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    saver.save(edit.getText().toString());
                }
            }
        });
        sections.addView(card);
    }

    private void addSpinnerRow(String title, int entriesRes, int valuesRes,
                               String current, final SpinnerSaver saver) {
        LinearLayout card = card();
        card.addView(rowLabel(title));
        final Spinner spinner = new Spinner(this);
        String[] entries = getResources().getStringArray(entriesRes);
        final String[] values = getResources().getStringArray(valuesRes);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item, entries);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackgroundResource(R.drawable.bg_input);
        spinner.setPadding(dp(8), dp(2), dp(8), dp(2));
        spinner.setPopupBackgroundResource(R.drawable.bg_popup);
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
        card.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sections.addView(card);
    }

    private void addSwitchRow(String title, boolean checked, final CheckSaver saver) {
        LinearLayout card = card();
        card.addView(rowLabel(title));
        final Switch sw = new Switch(this);
        sw.setChecked(checked);
        // Material-style track/thumb tinting (framework API 21+)
        int accent = getResources().getColor(R.color.accent);
        int trackOff = getResources().getColor(R.color.stroke);
        sw.setTrackTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        }, new int[]{
                Color.argb(0x66, Color.red(accent), Color.green(accent), Color.blue(accent)),
                trackOff
        }));
        sw.setThumbTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        }, new int[]{
                Color.WHITE,
                Color.argb(0xFF, 0x5A, 0x66, 0x72)
        }));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saver.save(sw.isChecked());
            }
        });
        card.addView(sw);
        sections.addView(card);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
