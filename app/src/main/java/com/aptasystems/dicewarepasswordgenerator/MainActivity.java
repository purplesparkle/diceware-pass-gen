package com.aptasystems.dicewarepasswordgenerator;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import java.security.SecureRandom;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_ENTER_DICE_VALUES = 1;
    private static final int DEFAULT_PASSWORD_LENGTH = 6;

    // One trillion guesses per second.
    private static final double NSA_GUESSES_PER_SECOND = 1000000000000.0;

    private static final String STATE_RANDOM_MECHANISM = "randomMechanism";
    private static final String STATE_PASSWORD_LENGTH = "passwordLength";
    private static final String STATE_PASSWORD = "password";

    // Widgets.
    private TextView _passwordLengthInfo;
    private RadioButton _androidPrngRadioButton;
    private RadioButton _randomOrgRadioButton;
    private RadioButton _diceRadioButton;
    private SeekBar _passwordLengthSeekBar;
    private TextView _passwordTextView;
    private Button _copyToClipboardButton;

    private GeneratePasswordTask _generatePasswordTask;

    // Tracks whether we've just rotated the screen.  Gets set and unset in the beginning and end of onCreate().
    private boolean _justRotated = false;

    static {
        PRNGFixes.apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            _justRotated = true;
        }

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Grab our widgets to use later.
        _passwordLengthInfo = (TextView) findViewById(R.id.text_view_password_length_info);
        _androidPrngRadioButton = (RadioButton) findViewById(R.id.radio_android_prng);
        _randomOrgRadioButton = (RadioButton) findViewById(R.id.radio_random_org);
        _diceRadioButton = (RadioButton) findViewById(R.id.radio_dice);
        _passwordLengthSeekBar = (SeekBar) findViewById(R.id.seek_bar_password_length);
        _passwordTextView = (TextView) findViewById(R.id.password_text_view);
        _copyToClipboardButton = (Button) findViewById(R.id.copy_to_clipboard);

        // Set up our seekbar listener.
        _passwordLengthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                // If this flag is true, we're recreating the activity after a rotation.  In this case, don't do anything.
                if (_justRotated) {
                    return;
                }

                updatePasswordLengthInfo();

                // Android PRNG: Generate a new password.
                // Random.org: Do nothing.
                // Dice: Show the 'no password' message and disable the clipboard button.
                if (_androidPrngRadioButton.isChecked()) {
                    newAndroidPassword();
                } else if (_diceRadioButton.isChecked()) {
                    _passwordTextView.setText(getResources().getString(R.string.no_password));
                    _copyToClipboardButton.setEnabled(false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Noop.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Android PRNG: Do nothing; password is recreated in onProgressChanged().
                // Random.org: Generate a new password.
                // Dice: Do nothing; the "new password" button must be tapped.
                if (_randomOrgRadioButton.isChecked()) {
                    newRandomOrgPassword();
                }
            }
        });

        // Handle restoration from a saved instance state: which radio button is checked and the selected password length.
        int radioButtonChecked = R.id.radio_android_prng;
        int passwordLength = DEFAULT_PASSWORD_LENGTH;
        if (savedInstanceState != null) {
            radioButtonChecked = savedInstanceState.getInt(STATE_RANDOM_MECHANISM, R.id.radio_android_prng);
            passwordLength = savedInstanceState.getInt(STATE_PASSWORD_LENGTH, DEFAULT_PASSWORD_LENGTH);
        }

        // Check the appropriate radio button, set the random mechanism, and set the password length seek bar.
        ((RadioButton) findViewById(radioButtonChecked)).setChecked(true);
        setRandomMechanism(radioButtonChecked);
        _passwordLengthSeekBar.setProgress(passwordLength - 1);

        // Restore the password if applicable.
        if (savedInstanceState != null) {
            String password = savedInstanceState.getString(STATE_PASSWORD);
            _passwordTextView.setText(password);
            _copyToClipboardButton.setEnabled(true);
        }

        _justRotated = false;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        int radioButtonChecked = 0;
        if (_androidPrngRadioButton.isChecked()) {
            radioButtonChecked = _androidPrngRadioButton.getId();
        } else if (_randomOrgRadioButton.isChecked()) {
            radioButtonChecked = _randomOrgRadioButton.getId();
        } else if (_diceRadioButton.isChecked()) {
            radioButtonChecked = _diceRadioButton.getId();
        }
        outState.putInt(STATE_RANDOM_MECHANISM, radioButtonChecked);
        outState.putInt(STATE_PASSWORD_LENGTH, _passwordLengthSeekBar.getProgress());
        outState.putString(STATE_PASSWORD, _passwordTextView.getText().toString());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
//        if (id == R.id.action_help) {
//            return true;
//        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == REQUEST_CODE_ENTER_DICE_VALUES) {

            if (resultCode == RESULT_OK) {

                final List<Integer> dieValues = (List<Integer>) data.getSerializableExtra(EnterDiceValuesActivity.EXTRA_DIE_VALUES);

                // Cancel any existing password generation task.
                if (_generatePasswordTask != null && !_generatePasswordTask.isCancelled()) {
                    _generatePasswordTask.cancel(true);
                }

                // Create a new password generation task to feed numbers from the enter dice values activity.
                _generatePasswordTask = new GeneratePasswordTask(this) {
                    @Override
                    public void generateRandomNumbers(int count) {

                        for (Integer value : dieValues) {
                            _numberQueue.offer(value);
                        }
                    }

                    @Override
                    protected void onPostExecute(String s) {
                        if (!isCancelled() && s != null) {
                            _passwordTextView.setText(s);
                            _copyToClipboardButton.setEnabled(true);
                        } else if (s == null) {
                            _passwordTextView.setText(getResources().getText(R.string.password_gen_failed));
                        }
                        super.onPostExecute(s);
                    }
                };

                int length = _passwordLengthSeekBar.getProgress() + 1;
                _generatePasswordTask.execute(length);

            } else {

                // Noop.

            }
        }
    }

    /**
     * Copy the password to the clipboard.
     *
     * @param view
     */
    public void copyToClipboard(View view) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("label", _passwordTextView.getText().toString());
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    /**
     * Update the text that shows the information about how long the password will take to brute
     * force.
     */
    private void updatePasswordLengthInfo() {

        int passwordLength = _passwordLengthSeekBar.getProgress() + 1;

        double wordCount = Diceware.getInstance(this).getWordCount();
        double permutations = Math.pow(wordCount, (double) passwordLength);
        double timeForAllPermutations = permutations / NSA_GUESSES_PER_SECOND;

        double[] timeDivisors = new double[]{2.0, 60.0, 60.0, 24.0, 365.25, 10.0, 10.0, 10.0, 1000.0, 10.0, 10.0, 5.0};
        int[] timeStrings = new int[]{
                R.string.duration_seconds,
                R.string.duration_minutes,
                R.string.duration_hours,
                R.string.duration_days,
                R.string.duration_years,
                R.string.duration_decades,
                R.string.duration_centuries,
                R.string.duration_millenia,
                R.string.duration_ages,
                R.string.duration_epochs,
                R.string.duration_eras,
                R.string.duration_eons};
        double unitThreshold = 1.0;
        double largestThreshold = 1000.0;
        double[] timeResults = new double[timeDivisors.length];

        double latestResult = timeForAllPermutations;
        for (int ii = 0; ii < timeDivisors.length; ii++) {
            timeResults[ii] = latestResult / timeDivisors[ii];
            latestResult = timeResults[ii];
        }

        Resources res = getResources();
        String text = null;

        // Case where we have more than the threshold of the largest unit.
        if (timeResults[timeResults.length - 1] >= largestThreshold) {
            text = res.getString(R.string.password_length_forever);
        }

        // Go through the time results and pick one if we didn't already.
        if (text == null) {
            for (int ii = timeDivisors.length - 1; ii >= 0; ii--) {
                if (timeResults[ii] >= unitThreshold) {
                    text = String.format(res.getString(R.string.password_length_segment), (int) timeResults[ii], res.getString(timeStrings[ii]));
                    break;
                }
            }
        }

        // Case where we have less than one of the smallest unit.
        if (timeResults[0] < unitThreshold) {
            text = res.getString(R.string.password_length_zero);
        }

        _passwordLengthInfo.setText(String.format(res.getString(R.string.password_length_info), text));
        _passwordLengthInfo.setVisibility(View.VISIBLE);
    }

    /**
     * Set the random mechanism (handles radio button clicks).
     *
     * @param view
     */
    public void setRandomMechanism(View view) {
        setRandomMechanism(view.getId());
    }

    private void setRandomMechanism(int viewId) {
        switch (viewId) {
            case R.id.radio_android_prng:
                if (!_justRotated) {
                    newAndroidPassword();
                }
                break;
            case R.id.radio_random_org:
                _copyToClipboardButton.setEnabled(false);
                if (!_justRotated) {
                    newRandomOrgPassword();
                }
                break;
            case R.id.radio_dice:
                if (!_justRotated) {
                    _passwordTextView.setText(getResources().getString(R.string.no_password));
                    _copyToClipboardButton.setEnabled(false);
                }
                break;
        }
    }

    /**
     * Handle the "new password" button tap.
     *
     * @param view
     */
    public void newPassword(View view) {
        if (_androidPrngRadioButton.isChecked()) {
            newAndroidPassword();
        } else if (_randomOrgRadioButton.isChecked()) {
            newRandomOrgPassword();
        } else if (_diceRadioButton.isChecked()) {
            newDicePassword();
        }
    }

    /**
     * Use the android pseudorandom number generator to generate a new Diceware password.
     */
    private void newAndroidPassword() {

        if (_generatePasswordTask != null && !_generatePasswordTask.isCancelled()) {
            _generatePasswordTask.cancel(true);
        }

        _generatePasswordTask = new GeneratePasswordTask(this) {
            @Override
            public void generateRandomNumbers(int count) {

                SecureRandom secureRandom = new SecureRandom();
                for (int ii = 0; ii < count; ii++) {
                    _numberQueue.offer(secureRandom.nextInt(6) + 1);
                }
            }

            @Override
            protected void onPostExecute(String s) {
                if (!isCancelled() && s != null) {
                    _passwordTextView.setText(s);
                    _copyToClipboardButton.setEnabled(true);
                } else if( s == null) {
                    _passwordTextView.setText(getResources().getText(R.string.password_gen_failed));
                }
                super.onPostExecute(s);
            }
        };

        int length = _passwordLengthSeekBar.getProgress() + 1;
        _generatePasswordTask.execute(length);
    }

    /**
     * Use data from random.org to generate a new Diceware password.
     */
    private void newRandomOrgPassword() {

        _passwordTextView.setText(getResources().getString(R.string.random_org_fetching));
        _copyToClipboardButton.setEnabled(false);

        if (_generatePasswordTask != null && !_generatePasswordTask.isCancelled()) {
            _generatePasswordTask.cancel(true);
        }

        _generatePasswordTask = new GeneratePasswordTask(this) {
            @Override
            public void generateRandomNumbers(int count) {
                Resources res = getResources();
                String url = String.format(res.getString(R.string.random_org_url), count);

                RequestQueue queue = Volley.newRequestQueue(MainActivity.this);

                StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                        new Response.Listener<String>() {
                            @Override
                            public void onResponse(String response) {
                                String[] tokens = response.split("\n");
                                for (String token : tokens) {
                                    Integer integer = Integer.parseInt(token);
                                    _numberQueue.offer(integer);
                                }
                            }
                        }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        _passwordTextView.setText(getResources().getString(R.string.random_org_error));
                        _copyToClipboardButton.setEnabled(false);
                    }
                });
                queue.add(stringRequest);
            }

            @Override
            protected void onPostExecute(String s) {
                if (!isCancelled() && s != null) {
                    _passwordTextView.setText(s);
                    _copyToClipboardButton.setEnabled(true);
                } else if (s == null) {
                    _passwordTextView.setText(getResources().getText(R.string.password_gen_failed));
                }
                super.onPostExecute(s);
            }
        };

        int length = _passwordLengthSeekBar.getProgress() + 1;
        _generatePasswordTask.execute(length);
    }

    /**
     * Start the activity to collect die rolls. When the activity is finished, those rolls will be
     * used to generate a new Dicware password.
     */
    private void newDicePassword() {
        // Jump over to the dice activity to collect the numbers.
        Intent intent = new Intent(this, EnterDiceValuesActivity.class);
        intent.putExtra(EnterDiceValuesActivity.EXTRA_REQUIRED_ROLL_COUNT, (_passwordLengthSeekBar.getProgress() + 1) * Diceware.DICE_PER_WORD);
        startActivityForResult(intent, REQUEST_CODE_ENTER_DICE_VALUES);
    }

}

